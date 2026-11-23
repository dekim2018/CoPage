import type { Node as PmNode, Schema } from '@tiptap/pm/model'
import { Selection, TextSelection } from '@tiptap/pm/state'
import type { EditorState, Transaction } from '@tiptap/pm/state'
import type { Component } from '../ot/delta'
import type { Block, Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { deleteBlock, insertBlock, mergeBlock, splitBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { uuidv7 } from '../client/ids'
import { diffText } from './textDiff'

/*
 * 편집기(ProseMirror) 문서와 연산 사이를 잇는 순수 함수들 (docs/10-FRONTEND.md 12.2). 화면을 몰라서 Vitest 에서 바로 돌린다.
 *
 * 편집기 문서는 [제목, 문단...] 이다. 제목은 루트 블록, 문단은 루트 바로 아래의 보이는 블록을 화면 순서대로 늘어놓은 것이다.
 * 위치는 두 가지가 있다. 편집기는 문서 전체의 위치를 쓰고, 연산은 (블록 ID, 블록 안 위치)를 쓴다.
 */

/** 트랜잭션에 이 표시가 있으면 서버나 어댑터가 만든 변경이다. 다시 연산으로 만들지 않는다. */
export const REMOTE = 'copage-remote'

/** 편집기 문서의 블록 하나. id 가 없으면 편집기에서 새로 생긴 노드다. */
export interface PmBlock {
  readonly id: string | null
  readonly text: string
  readonly title: boolean
}

/** 블록 안의 위치. 블록이 바뀌어도 커서를 지킬 때 쓴다. */
export interface BlockPosition {
  readonly blockId: string
  readonly offset: number
}

/** 화면에 보이는 루트 아래 블록. 삭제 표시된 블록은 빠진다. 문단만 다루므로 그 아래 블록은 아직 그리지 않는다. */
export function visibleChildren(doc: Document): Block[] {
  return doc.children(doc.rootId).filter((b) => !b.deleted)
}

export function readBlocks(pm: PmNode): PmBlock[] {
  const out: PmBlock[] = []
  pm.forEach((node) => {
    out.push({ id: (node.attrs.blockId as string | null) ?? null, text: node.textContent, title: node.type.name === 'title' })
  })
  return out
}

/** 문서를 편집기 문서로 만든다. */
export function toPm(schema: Schema, doc: Document): PmNode {
  const root = doc.get(doc.rootId) as Block
  const nodes = [blockNode(schema, 'title', root.id, root.text.plainText())]
  for (const child of visibleChildren(doc)) {
    nodes.push(blockNode(schema, 'paragraph', child.id, child.text.plainText()))
  }
  return schema.node('doc', null, nodes)
}

function blockNode(schema: Schema, type: string, id: string, text: string): PmNode {
  return schema.node(type, { blockId: id }, text.length === 0 ? [] : [schema.text(text)])
}

/** 편집기 위치를 블록 안 위치로. 블록 사이(경계)면 가까운 블록의 끝이나 처음으로 본다. */
export function toBlockPosition(pm: PmNode, pos: number): BlockPosition | null {
  let result: BlockPosition | null = null
  let start = 0
  pm.forEach((node) => {
    const contentStart = start + 1
    const contentEnd = contentStart + node.content.size
    if (result === null && pos <= contentEnd + 1) {
      const id = node.attrs.blockId as string | null
      if (id !== null) {
        result = { blockId: id, offset: Math.max(0, Math.min(pos, contentEnd) - contentStart) }
      }
    }
    start += node.nodeSize
  })
  return result
}

/** 블록 안 위치를 편집기 위치로. 블록이 없으면 null. */
export function toPmPosition(pm: PmNode, position: BlockPosition): number | null {
  let result: number | null = null
  let start = 0
  pm.forEach((node) => {
    if (result === null && node.attrs.blockId === position.blockId) {
      result = start + 1 + Math.min(position.offset, node.content.size)
    }
    start += node.nodeSize
  })
  return result
}

/**
 * 사용자가 편집기에서 한 변경(before → after)을 연산으로 만든다. 블록 ID 로 짝을 맞춘다.
 *
 * - 같은 ID 의 글이 바뀌었으면 text
 * - 사라진 ID 는 deleteBlock (여러 블록에 걸친 선택을 지우면 생긴다)
 * - ID 가 없거나 앞에서 이미 나온 ID 는 새 블록이라 insertBlock. 순서 키는 화면 순서상 앞뒤 블록 사이로 만든다
 *
 * Enter 와 Backspace 는 어댑터가 가로채 split, merge 를 직접 만들므로 여기로 오지 않는다.
 */
export function localOps(view: Document, before: PmNode, after: PmNode): Op[] {
  const oldBlocks = readBlocks(before)
  const newBlocks = readBlocks(after)
  const oldText = new Map(oldBlocks.filter((b) => b.id !== null).map((b) => [b.id as string, b.text]))
  const ops: Op[] = []
  const seen = new Set<string>()
  const kept = new Set<string>()
  for (const block of newBlocks) {
    if (block.id !== null && !seen.has(block.id) && oldText.has(block.id)) {
      kept.add(block.id)
    }
    if (block.id !== null) {
      seen.add(block.id)
    }
  }
  for (const block of oldBlocks) {
    if (block.id !== null && !block.title && !kept.has(block.id)) {
      ops.push(deleteBlock(block.id, true))
    }
  }

  // 새 블록의 순서 키를 정하려고, 화면 순서대로 블록 ID 와 순서 키를 들고 간다.
  const posOf = new Map(visibleChildren(view).map((b) => [b.id, b.pos]))
  const placed = new Set<string>()
  let previousPos: string | null = null
  const paragraphs = newBlocks.filter((b) => !b.title)
  for (const [i, block] of paragraphs.entries()) {
    const existing = block.id !== null && kept.has(block.id) && !placed.has(block.id)
    if (existing) {
      const id = block.id as string
      placed.add(id)
      previousPos = posOf.get(id) ?? previousPos
      const delta = diffText(oldText.get(id) ?? '', block.text)
      if (!delta.isNoop()) {
        ops.push(textOp(id, delta))
      }
      continue
    }
    const nextPos = nextExistingPos(paragraphs, i + 1, kept, placed, posOf)
    const pos = keyBetween(previousPos, nextPos)
    ops.push(insertBlock(uuidv7(), view.rootId, pos, 'paragraph', undefined, RichText.of(block.text)))
    previousPos = pos
  }

  const title = newBlocks.find((b) => b.title)
  const oldTitle = oldBlocks.find((b) => b.title)
  if (title !== undefined && oldTitle !== undefined) {
    const delta = diffText(oldTitle.text, title.text)
    if (!delta.isNoop()) {
      ops.push(textOp(view.rootId, delta))
    }
  }
  return ops
}

function nextExistingPos(
  paragraphs: PmBlock[],
  from: number,
  kept: Set<string>,
  placed: Set<string>,
  posOf: Map<string, string>,
): string | null {
  for (let i = from; i < paragraphs.length; i++) {
    const id = (paragraphs[i] as PmBlock).id
    if (id !== null && kept.has(id) && !placed.has(id)) {
      return posOf.get(id) ?? null
    }
  }
  return null
}

/**
 * 두 순서 키 사이의 키. 동시에 같은 자리에 넣어 두 키가 같아졌거나 순서가 꼬였으면 앞 키 바로 뒤로 놓는다. 이 경우 뒤 블록과의
 * 순서는 블록 ID 순서를 따르므로 아주 드물게 뒤 블록 다음에 보일 수 있다.
 */
export function keyBetween(before: string | null, after: string | null): string {
  try {
    return between(before, after)
  } catch {
    return between(before, null)
  }
}

/** Enter: 문단을 커서에서 나눈다. 새 블록은 원래 블록 바로 뒤에 놓인다. */
export function splitAt(view: Document, position: BlockPosition): { ops: Op[]; cursor: BlockPosition } {
  const children = visibleChildren(view)
  const index = children.findIndex((b) => b.id === position.blockId)
  const block = children[index] as Block
  const next = children[index + 1]
  const newId = uuidv7()
  const op = splitBlock(
    block.id,
    position.offset,
    newId,
    view.rootId,
    keyBetween(block.pos, next?.pos ?? null),
    'paragraph',
  )
  return { ops: [op], cursor: { blockId: newId, offset: 0 } }
}

/** 제목에서 Enter: 본문 맨 앞에 빈 문단을 넣는다. */
export function newFirstParagraph(view: Document): { ops: Op[]; cursor: BlockPosition } {
  const first = visibleChildren(view)[0]
  const newId = uuidv7()
  const op = insertBlock(newId, view.rootId, keyBetween(null, first?.pos ?? null), 'paragraph')
  return { ops: [op], cursor: { blockId: newId, offset: 0 } }
}

/**
 * 블록 처음의 Backspace: 앞 문단 끝에 합친다. 앞이 제목이면 합치지 않고(루트에는 합칠 수 없다), 빈 문단이면 지우고 제목 끝으로
 * 간다. 할 일이 없으면 null.
 */
export function mergeBackward(view: Document, blockId: string): { ops: Op[]; cursor: BlockPosition } | null {
  const children = visibleChildren(view)
  const index = children.findIndex((b) => b.id === blockId)
  if (index < 0) {
    return null
  }
  const block = children[index] as Block
  const previous = children[index - 1]
  if (previous === undefined) {
    if (block.text.length > 0) {
      return null
    }
    const root = view.get(view.rootId) as Block
    return { ops: [deleteBlock(block.id, true)], cursor: { blockId: root.id, offset: root.text.length } }
  }
  return {
    ops: [mergeBlock(block.id, previous.id, previous.text.length)],
    cursor: { blockId: previous.id, offset: previous.text.length },
  }
}

/** 블록 끝의 Delete: 다음 문단을 이 블록 끝에 합친다. */
export function mergeForward(view: Document, blockId: string): { ops: Op[]; cursor: BlockPosition } | null {
  const children = visibleChildren(view)
  const index = children.findIndex((b) => b.id === blockId)
  const block = children[index]
  const next = children[index + 1]
  if (block === undefined || next === undefined) {
    return null
  }
  return {
    ops: [mergeBlock(next.id, block.id, block.text.length)],
    cursor: { blockId: block.id, offset: block.text.length },
  }
}

/**
 * 화면 문서를 편집기에 반영하는 트랜잭션. 바뀐 것이 없으면 null.
 *
 * 블록의 순서와 ID 가 같으면 글이 바뀐 블록만 그 부분을 바꾼다. 편집기가 바뀐 범위에 맞춰 내 커서를 옮겨 준다. 블록이 생기거나
 * 사라지거나 순서가 바뀌면 문서 전체를 바꾸고 커서를 (블록 ID, 위치)로 되돌린다. cursor 를 주면 그 자리로 커서를 놓는다.
 */
export function renderView(state: EditorState, view: Document, cursor?: BlockPosition): Transaction | null {
  const target = toPm(state.schema, view)
  const current = state.doc
  const tr = state.tr.setMeta(REMOTE, true).setMeta('addToHistory', false)
  const currentBlocks = readBlocks(current)
  const targetBlocks = readBlocks(target)
  const sameShape =
    currentBlocks.length === targetBlocks.length && currentBlocks.every((b, i) => b.id === targetBlocks[i]?.id)

  if (sameShape) {
    // 뒤에서부터 바꿔야 앞 블록의 위치가 흔들리지 않는다.
    const starts: number[] = []
    let start = 0
    current.forEach((node) => {
      starts.push(start)
      start += node.nodeSize
    })
    for (let i = currentBlocks.length - 1; i >= 0; i--) {
      const before = (currentBlocks[i] as PmBlock).text
      const after = (targetBlocks[i] as PmBlock).text
      if (before === after) {
        continue
      }
      const delta = diffText(before, after)
      const [retained, removed, inserted] = parts(delta.components)
      const from = (starts[i] as number) + 1 + retained
      tr.replaceWith(from, from + removed, inserted.length === 0 ? [] : state.schema.text(inserted))
    }
  } else {
    const anchor = toBlockPosition(current, state.selection.anchor)
    const head = toBlockPosition(current, state.selection.head)
    tr.replaceWith(0, current.content.size, target.content)
    const a = anchor === null ? null : toPmPosition(tr.doc, anchor)
    const h = head === null ? null : toPmPosition(tr.doc, head)
    if (a !== null && h !== null) {
      tr.setSelection(TextSelection.create(tr.doc, a, h))
    } else {
      tr.setSelection(Selection.atStart(tr.doc))
    }
  }

  if (cursor !== undefined) {
    const pos = toPmPosition(tr.doc, cursor)
    if (pos !== null) {
      tr.setSelection(TextSelection.create(tr.doc, pos))
    }
  }
  return tr.docChanged || cursor !== undefined ? tr : null
}

/** diffText 가 만드는 [retain(n)?, delete(m)?, insert(s)?] 모양을 (n, m, s) 로 읽는다. */
function parts(components: readonly Component[]): [number, number, string] {
  let retained = 0
  let removed = 0
  let inserted = ''
  for (const c of components) {
    if (c.kind === 'retain') {
      retained += c.count
    } else if (c.kind === 'delete') {
      removed += c.count
    } else {
      inserted += c.text
    }
  }
  return [retained, removed, inserted]
}
