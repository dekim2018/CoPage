import type { Node as PmNode, Schema } from '@tiptap/pm/model'
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state'
import type { EditorState, Transaction } from '@tiptap/pm/state'
import * as A from '../ot/attrs'
import type { Attrs, AttrValue } from '../ot/attrs'
import type { Component } from '../ot/delta'
import type { Block, Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { TYPE_KEY, deleteBlock, insertBlock, mergeBlock, moveBlock, setProps, splitBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import type { RichText } from '../ot/richText'
import { uuidv7 } from '../client/ids'
import {
  PROP_DEFAULTS,
  SYNCED_PROPS,
  blockTypeOf,
  contentOf,
  isBlockType,
  markOf,
  marksOfAttrs,
  nodeName,
  richOf,
  typeAfterSplit,
} from './blocks'
import { diffRich } from './textDiff'

/*
 * 편집기(ProseMirror) 문서와 연산 사이를 잇는 순수 함수들 (docs/10-FRONTEND.md 12.2). 화면을 몰라서 Vitest 에서 바로 돌린다.
 *
 * 편집기 문서는 [제목, 블록...] 이다. 제목은 루트 블록이고, 블록은 루트 아래의 보이는 블록을 깊이 우선으로 화면 순서대로 늘어놓은
 * 것이다. 트리에서의 깊이는 depth 속성으로 그린다. 접힌 토글의 자식은 늘어놓지 않는다.
 *
 * 위치는 두 가지가 있다. 편집기는 문서 전체의 위치를 쓰고, 연산은 (블록 ID, 블록 안 위치)를 쓴다.
 */

/** 트랜잭션에 이 표시가 있으면 서버나 어댑터가 만든 변경이다. 다시 연산으로 만들지 않는다. */
export const REMOTE = 'copage-remote'

/** 내 화면에만 적용하는 상태. 접힌 토글의 ID. */
export interface Display {
  readonly collapsed: ReadonlySet<string>
}

export const NO_DISPLAY: Display = { collapsed: new Set() }

/** 화면 순서대로 늘어놓은 블록 하나. */
export interface FlatBlock {
  readonly block: Block
  readonly depth: number
  /** 번호 목록의 번호. 같은 부모 아래 바로 앞에 이어진 번호 목록 수 + 1. */
  readonly number: number
  readonly hasChildren: boolean
}

/** 편집기 문서의 블록 하나. id 가 없으면 편집기에서 새로 생긴 노드다. */
export interface PmBlock {
  readonly id: string | null
  readonly title: boolean
  readonly type: string
  /** 문서에 저장하는 속성만 (SYNCED_PROPS). */
  readonly props: Attrs
  readonly text: RichText
}

/** 블록 안의 위치. 블록이 바뀌어도 커서를 지킬 때 쓴다. */
export interface BlockPosition {
  readonly blockId: string
  readonly offset: number
}

/** 보이는 자식. 삭제 표시된 블록은 빠진다. */
export function visibleChildren(doc: Document, parentId: string): Block[] {
  return doc.children(parentId).filter((b) => !b.deleted)
}

/** 루트 아래 블록을 깊이 우선으로 화면 순서대로 늘어놓는다. */
export function flatten(doc: Document, display: Display = NO_DISPLAY): FlatBlock[] {
  const out: FlatBlock[] = []
  const visit = (parentId: string, depth: number) => {
    let number = 0
    for (const block of visibleChildren(doc, parentId)) {
      number = block.type === 'numbered' ? number + 1 : 0
      const kids = visibleChildren(doc, block.id)
      out.push({ block, depth, number: Math.max(number, 1), hasChildren: kids.length > 0 })
      if (!(block.type === 'toggle' && display.collapsed.has(block.id))) {
        visit(block.id, depth + 1)
      }
    }
  }
  visit(doc.rootId, 0)
  return out
}

/** 문서를 편집기 문서로 만든다. */
export function toPm(schema: Schema, doc: Document, display: Display = NO_DISPLAY): PmNode {
  const root = doc.get(doc.rootId) as Block
  const nodes = [schema.node('title', { blockId: root.id }, contentOf(schema, root.text, false))]
  for (const flat of flatten(doc, display)) {
    nodes.push(blockNode(schema, flat, display))
  }
  return schema.node('doc', null, nodes)
}

function blockNode(schema: Schema, flat: FlatBlock, display: Display): PmNode {
  const { block } = flat
  // 모르는 종류(나중에 생길 종류)는 문단으로 그린다. 종류를 바꾸지는 않는다.
  const typeName = isBlockType(block.type) ? block.type : 'paragraph'
  const type = schema.nodes[nodeName(typeName)] as NonNullable<Schema['nodes'][string]>
  const attrs: Record<string, unknown> = { blockId: block.id, depth: flat.depth }
  for (const key of isBlockType(block.type) ? SYNCED_PROPS[block.type] : []) {
    attrs[key] = A.has(block.props, key) ? block.props[key] : (PROP_DEFAULTS[key] ?? null)
  }
  if (typeName === 'numbered') {
    attrs.number = flat.number
  }
  if (typeName === 'toggle') {
    attrs.collapsed = display.collapsed.has(block.id)
    attrs.hasChildren = flat.hasChildren
  }
  if (type.isAtom) {
    return type.create(attrs)
  }
  return type.create(attrs, contentOf(schema, block.text, type.spec.marks !== ''))
}

export function readBlocks(pm: PmNode): PmBlock[] {
  const out: PmBlock[] = []
  pm.forEach((node) => {
    const type = blockTypeOf(node.type.name)
    const props: Record<string, AttrValue> = {}
    for (const key of type === null ? [] : SYNCED_PROPS[type]) {
      props[key] = node.attrs[key] as AttrValue
    }
    out.push({
      id: (node.attrs.blockId as string | null) ?? null,
      title: node.type.name === 'title',
      type: type ?? 'paragraph',
      props: A.copy(props),
      text: richOf(node),
    })
  })
  return out
}

/**
 * 편집기 위치를 블록 안 위치로. 블록 사이(경계)면 가까운 블록의 끝이나 처음으로 본다. ID 없는 노드는 건너뛴다.
 *
 * 구분선 같은 원자 노드는 안쪽이 없이 한 칸만 차지한다. 그 노드 바로 앞 위치만 그 노드로 보고, 뒤 위치는 다음 블록에 넘긴다.
 * 글이 있는 블록처럼 계산하면 아래 문단의 처음이 구분선으로 잡힌다.
 */
export function toBlockPosition(pm: PmNode, pos: number): BlockPosition | null {
  let result: BlockPosition | null = null
  let start = 0
  pm.forEach((node) => {
    const id = node.attrs.blockId as string | null
    if (result === null && id !== null) {
      if (node.isAtom) {
        if (pos <= start) {
          result = { blockId: id, offset: 0 }
        }
      } else {
        const contentStart = start + 1
        const contentEnd = contentStart + node.content.size
        if (pos <= contentEnd + 1) {
          result = { blockId: id, offset: Math.max(0, Math.min(pos, contentEnd) - contentStart) }
        }
      }
    }
    start += node.nodeSize
  })
  return result
}

/** 블록 안 위치를 편집기 위치로. 블록이 없으면 null. 원자 노드(구분선)는 그 노드의 시작 위치다. */
export function toPmPosition(pm: PmNode, position: BlockPosition): number | null {
  let result: number | null = null
  let start = 0
  pm.forEach((node) => {
    if (result === null && node.attrs.blockId === position.blockId) {
      result = node.isAtom ? start : start + 1 + Math.min(position.offset, node.content.size)
    }
    start += node.nodeSize
  })
  return result
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

/** 형제 사이에서 바로 다음 보이는 형제. */
function nextSibling(doc: Document, block: Block): Block | undefined {
  const siblings = visibleChildren(doc, block.parentId as string)
  return siblings[siblings.findIndex((b) => b.id === block.id) + 1]
}

function previousSibling(doc: Document, block: Block): Block | undefined {
  const siblings = visibleChildren(doc, block.parentId as string)
  return siblings[siblings.findIndex((b) => b.id === block.id) - 1]
}

/**
 * 화면에서 afterId 블록 바로 다음 줄에 새 블록을 놓을 자리. 그 블록의 자식이 펼쳐져 보이면 첫 자식으로, 아니면 바로 다음
 * 형제로 놓는다. afterId 가 null(제목 바로 다음)이면 루트의 첫 자식이다.
 */
export function placeAfter(
  doc: Document,
  afterId: string | null,
  display: Display = NO_DISPLAY,
): { parentId: string; pos: string } {
  if (afterId === null || afterId === doc.rootId) {
    const first = visibleChildren(doc, doc.rootId)[0]
    return { parentId: doc.rootId, pos: keyBetween(null, first?.pos ?? null) }
  }
  const block = doc.get(afterId) as Block
  const kids = visibleChildren(doc, block.id)
  const expanded = !(block.type === 'toggle' && display.collapsed.has(block.id))
  if (kids.length > 0 && expanded) {
    return { parentId: block.id, pos: keyBetween(null, (kids[0] as Block).pos) }
  }
  return { parentId: block.parentId as string, pos: keyBetween(block.pos, nextSibling(doc, block)?.pos ?? null) }
}

/** 블록의 보이는 자식들을 블록 바로 뒤의 형제로 옮긴다. 합치거나 지우기 전에 자식이 함께 숨지 않게 한다 (6.6). */
function liftChildren(doc: Document, block: Block): Op[] {
  const ops: Op[] = []
  let previousPos = block.pos
  const next = nextSibling(doc, block)?.pos ?? null
  for (const kid of visibleChildren(doc, block.id)) {
    const pos = keyBetween(previousPos, next)
    ops.push(moveBlock(kid.id, block.parentId as string, pos))
    previousPos = pos
  }
  return ops
}

/**
 * 사용자가 편집기에서 한 변경(before → after)을 연산으로 만든다. 블록 ID 로 짝을 맞춘다.
 *
 * - 같은 ID 의 블록: 종류나 속성이 바뀌었으면 setProps, 글이나 서식이 바뀌었으면 text
 * - 사라진 ID: deleteBlock. 그 블록의 자식이 편집기에 남아 있으면 먼저 바로 뒤 형제로 옮긴다
 * - ID 가 없거나 앞에서 이미 나온 ID: 새 블록이라 insertBlock. 화면에서 바로 앞 블록 다음 줄 자리(placeAfter)에 놓는다
 *
 * 연산을 하나 만들 때마다 문서에 적용해 두고 다음 연산의 자리를 그 문서에서 찾는다. Enter, Backspace, Tab 은 어댑터가 가로채
 * 직접 만들므로 여기로 오지 않는다.
 */
export function localOps(view: Document, before: PmNode, after: PmNode, display: Display = NO_DISPLAY): Op[] {
  const oldBlocks = readBlocks(before)
  const newBlocks = readBlocks(after)
  const oldById = new Map(oldBlocks.filter((b) => b.id !== null).map((b) => [b.id as string, b]))
  const kept = new Set<string>()
  const seen = new Set<string>()
  for (const block of newBlocks) {
    if (block.id !== null && !seen.has(block.id) && oldById.has(block.id)) {
      kept.add(block.id)
    }
    if (block.id !== null) {
      seen.add(block.id)
    }
  }

  const ops: Op[] = []
  let work = view
  const push = (op: Op) => {
    ops.push(op)
    work = work.apply(op)
  }

  for (const block of oldBlocks) {
    if (block.id === null || block.title || kept.has(block.id)) {
      continue
    }
    const current = work.get(block.id)
    if (current === undefined || current.deleted) {
      continue
    }
    for (const op of liftChildren(work, current)) {
      push(op)
    }
    push(deleteBlock(block.id, true))
  }

  let previousId: string | null = null
  const placed = new Set<string>()
  for (const block of newBlocks) {
    if (block.title) {
      const old = oldBlocks.find((b) => b.title)
      if (old !== undefined) {
        const delta = diffRich(old.text, block.text)
        if (!delta.isNoop()) {
          push(textOp(view.rootId, delta))
        }
      }
      continue
    }
    if (block.id !== null && kept.has(block.id) && !placed.has(block.id)) {
      const id = block.id
      placed.add(id)
      const old = oldById.get(id) as PmBlock
      for (const op of propOps(id, old, block)) {
        push(op)
      }
      const delta = diffRich(old.text, block.text)
      if (!delta.isNoop()) {
        push(textOp(id, delta))
      }
      previousId = id
      continue
    }
    const newId = uuidv7()
    const place = placeAfter(work, previousId, display)
    push(insertBlock(newId, place.parentId, place.pos, block.type, block.props, block.text))
    previousId = newId
  }
  return ops
}

/** 종류와 저장 속성의 차이를 setProps 로. */
function propOps(id: string, old: PmBlock, next: PmBlock): Op[] {
  const ops: Op[] = []
  if (old.type !== next.type) {
    ops.push(setProps(id, TYPE_KEY, next.type))
  }
  for (const [key, value] of Object.entries(next.props)) {
    if (!A.has(old.props, key) || !A.valueEquals(old.props[key], value)) {
      ops.push(setProps(id, key, value))
    }
  }
  return ops
}

/**
 * 연산이 블록을 옮겨 넣는 부모들. 내가 블록을 접힌 토글 아래로 옮기면(Tab, Shift+Tab 으로 따라온 형제) 그 블록이 숨어 사라진
 * 것처럼 보이므로, 바인딩은 이 부모들을 펼친다.
 */
export function moveTargets(ops: readonly Op[]): string[] {
  return ops.flatMap((op) => (op.op === 'moveBlock' ? [op.parentId] : []))
}

/** 어댑터가 가로챈 키 하나의 결과. ops 를 적용한 뒤 cursor 로 커서를 옮긴다. */
export interface Intent {
  readonly ops: Op[]
  readonly cursor: BlockPosition
}

/**
 * Enter. 블록을 커서에서 나눈다. 뒤쪽 블록은 목록, 할 일, 토글이면 같은 종류로 이어 가고 나머지는 문단이다. 자식이 펼쳐져 있으면
 * 뒤쪽 블록을 첫 자식으로, 아니면 바로 다음 형제로 놓는다. 빈 목록, 할 일, 토글, 인용, 콜아웃에서 Enter 를 치면 나누지 않고
 * 문단으로 바꾼다. 다른 편집기와 같은 동작이다.
 */
export function splitAt(view: Document, position: BlockPosition, display: Display = NO_DISPLAY): Intent {
  const block = view.get(position.blockId) as Block
  if (block.text.length === 0 && block.type !== 'paragraph' && block.type !== 'heading') {
    return { ops: [setProps(block.id, TYPE_KEY, 'paragraph')], cursor: position }
  }
  const type = typeAfterSplit(block.type)
  const place = placeAfter(view, block.id, display)
  const newId = uuidv7()
  const props: Record<string, AttrValue> = type === 'todo' ? { checked: false } : {}
  const op = splitBlock(block.id, position.offset, newId, place.parentId, place.pos, type, props)
  return { ops: [op], cursor: { blockId: newId, offset: 0 } }
}

/** 제목에서 Enter, 구분선을 고른 채 Enter: 그 아래에 빈 문단을 넣는다. */
export function newParagraphAfter(view: Document, afterId: string | null, display: Display = NO_DISPLAY): Intent {
  const place = placeAfter(view, afterId, display)
  const newId = uuidv7()
  return { ops: [insertBlock(newId, place.parentId, place.pos, 'paragraph')], cursor: { blockId: newId, offset: 0 } }
}

/**
 * 블록 처음의 Backspace.
 *
 * - 문단이 아니면 먼저 문단으로 바꾼다
 * - 바로 위 줄이 구분선이면 구분선을 지운다
 * - 바로 위 줄이 제목이면 합치지 않는다(루트에는 합칠 수 없다). 빈 블록이면 지우고 제목 끝으로 간다
 * - 그 밖에는 위 줄 블록 끝에 합친다. 자식이 있으면 먼저 바로 뒤 형제로 옮긴다 (6.6)
 *
 * 할 일이 없으면 null.
 */
export function mergeBackward(view: Document, blockId: string, display: Display = NO_DISPLAY): Intent | null {
  const flat = flatten(view, display)
  const index = flat.findIndex((f) => f.block.id === blockId)
  if (index < 0) {
    return null
  }
  const block = (flat[index] as FlatBlock).block
  const here = { blockId, offset: 0 }
  if (block.type !== 'paragraph') {
    return { ops: [setProps(blockId, TYPE_KEY, 'paragraph')], cursor: here }
  }
  const previous = flat[index - 1]?.block
  if (previous === undefined) {
    if (block.text.length > 0 || visibleChildren(view, block.id).length > 0) {
      return null
    }
    const root = view.get(view.rootId) as Block
    return { ops: [deleteBlock(block.id, true)], cursor: { blockId: root.id, offset: root.text.length } }
  }
  if (previous.type === 'divider') {
    return { ops: [deleteBlock(previous.id, true)], cursor: here }
  }
  return {
    ops: [...liftChildren(view, block), mergeBlock(block.id, previous.id, previous.text.length)],
    cursor: { blockId: previous.id, offset: previous.text.length },
  }
}

/** 블록 끝의 Delete: 아래 줄 블록을 이 블록 끝에 합친다. 아래 줄이 구분선이면 지운다. */
export function mergeForward(view: Document, blockId: string, display: Display = NO_DISPLAY): Intent | null {
  const flat = flatten(view, display)
  const index = flat.findIndex((f) => f.block.id === blockId)
  const block = flat[index]?.block
  const next = flat[index + 1]?.block
  if (block === undefined || next === undefined || block.type === 'divider') {
    return null
  }
  const here = { blockId, offset: block.text.length }
  if (next.type === 'divider') {
    return { ops: [deleteBlock(next.id, true)], cursor: here }
  }
  return { ops: [...liftChildren(view, next), mergeBlock(next.id, block.id, block.text.length)], cursor: here }
}

/** Tab: 바로 앞 형제의 마지막 자식으로 옮긴다 (E-4). 앞 형제가 없으면 null. */
export function indent(view: Document, position: BlockPosition): Intent | null {
  const block = view.get(position.blockId) as Block
  const previous = previousSibling(view, block)
  if (previous === undefined) {
    return null
  }
  const last = visibleChildren(view, previous.id).at(-1)
  return { ops: [moveBlock(block.id, previous.id, keyBetween(last?.pos ?? null, null))], cursor: position }
}

/**
 * Shift+Tab: 부모의 바로 다음 형제로 옮긴다. 블록 뒤에 있던 형제들은 이 블록의 자식으로 따라와 화면 순서가 그대로다. 이미 루트
 * 바로 아래면 null.
 */
export function outdent(view: Document, position: BlockPosition): Intent | null {
  const block = view.get(position.blockId) as Block
  if (block.parentId === view.rootId || block.parentId === null) {
    return null
  }
  const parent = view.get(block.parentId) as Block
  const siblings = visibleChildren(view, parent.id)
  const following = siblings.slice(siblings.findIndex((b) => b.id === block.id) + 1)
  const ops: Op[] = [
    moveBlock(block.id, parent.parentId as string, keyBetween(parent.pos, nextSibling(view, parent)?.pos ?? null)),
  ]
  let previousPos = visibleChildren(view, block.id).at(-1)?.pos ?? null
  for (const sibling of following) {
    const pos = keyBetween(previousPos, null)
    ops.push(moveBlock(sibling.id, block.id, pos))
    previousPos = pos
  }
  return { ops, cursor: position }
}

/**
 * 화면 문서를 편집기에 반영하는 트랜잭션. 바뀐 것이 없으면 null.
 *
 * 블록의 순서와 ID 가 같으면 바뀐 블록만 고친다. 종류가 같으면 속성(깊이, 번호, 체크 등)만 바꾸고, 글은 바뀐 부분만 바꾸며
 * 서식만 바뀐 범위는 마크만 바꾼다. 편집기가 바뀐 범위에 맞춰 내 커서를 옮겨 준다. ID 가 없는 노드(방금 생긴 블록)는 그 자리
 * 블록의 ID 를 속성으로만 붙인다. 종류가 바뀌어 노드를 바꿔야 하면 그 노드만 바꾼다.
 *
 * 블록이 생기거나 사라지거나 순서가 바뀌면 문서 전체를 바꾸고 커서를 (블록 ID, 위치)로 되돌린다. 커서가 있던 블록이 없어졌으면
 * 원래 위치에서 가장 가까운 곳에 둔다. cursor 를 주면 그 자리로 커서를 놓는다.
 */
export function renderView(
  state: EditorState,
  view: Document,
  cursor?: BlockPosition,
  display: Display = NO_DISPLAY,
): Transaction | null {
  const target = toPm(state.schema, view, display)
  const current = state.doc
  const tr = state.tr.setMeta(REMOTE, true).setMeta('addToHistory', false)
  const currentIds: (string | null)[] = []
  current.forEach((node) => currentIds.push((node.attrs.blockId as string | null) ?? null))
  const targetIds: string[] = []
  target.forEach((node) => targetIds.push(node.attrs.blockId as string))
  // 방금 생겨 아직 ID 가 없는 노드는 그 자리의 블록과 같은 것으로 본다. 연산이 그 순서대로 블록을 만들었다.
  const sameShape =
    currentIds.length === targetIds.length && currentIds.every((id, i) => id === null || id === targetIds[i])

  if (sameShape) {
    const starts: number[] = []
    let start = 0
    current.forEach((node) => {
      starts.push(start)
      start += node.nodeSize
    })
    // 뒤에서부터 바꿔야 앞 블록의 위치가 흔들리지 않는다.
    for (let i = targetIds.length - 1; i >= 0; i--) {
      patchNode(tr, starts[i] as number, current.child(i), target.child(i))
    }
  } else {
    const anchor = toBlockPosition(current, state.selection.anchor)
    const head = toBlockPosition(current, state.selection.head)
    tr.replaceWith(0, current.content.size, target.content)
    const a = anchor === null ? null : toPmPosition(tr.doc, anchor)
    const h = head === null ? null : toPmPosition(tr.doc, head)
    if (a !== null && h !== null && inText(tr.doc, a) && inText(tr.doc, h)) {
      tr.setSelection(TextSelection.create(tr.doc, a, h))
    } else if (h !== null) {
      tr.setSelection(selectionAt(tr.doc, h))
    } else {
      tr.setSelection(Selection.near(tr.doc.resolve(Math.min(state.selection.head, tr.doc.content.size))))
    }
  }

  if (cursor !== undefined) {
    const pos = toPmPosition(tr.doc, cursor)
    if (pos !== null) {
      tr.setSelection(selectionAt(tr.doc, pos))
    }
  }
  return tr.docChanged || cursor !== undefined ? tr : null
}

/** 글이 들어가는 블록 안의 위치인지. 글자 노드도 잎 노드라 isAtom 으로는 가를 수 없다. */
function inText(doc: PmNode, pos: number): boolean {
  return doc.resolve(pos).parent.inlineContent
}

/** pos 에 놓을 선택. 글 안이면 커서, 구분선 같은 블록 원자 노드 앞이면 그 노드를 고른다. */
function selectionAt(doc: PmNode, pos: number): Selection {
  if (inText(doc, pos)) {
    return TextSelection.create(doc, pos)
  }
  const node = doc.nodeAt(pos)
  if (node !== null && node.isBlock && node.isAtom) {
    return NodeSelection.create(doc, pos)
  }
  return Selection.near(doc.resolve(pos))
}

/** 같은 자리의 노드 하나를 target 처럼 고친다. */
function patchNode(tr: Transaction, start: number, current: PmNode, target: PmNode): void {
  // 원격 편집 하나는 보통 블록 하나만 바꾼다. 같은 노드는 글자 차이를 계산하지 않고 넘어간다(PERF-1). ID 가 아직 없는 노드는
  // 속성이 달라 여기서 걸리지 않는다.
  if (current.eq(target)) {
    return
  }
  if (current.type !== target.type || current.isAtom) {
    if (current.type !== target.type || !sameAttrs(current, target)) {
      tr.replaceWith(start, start + current.nodeSize, target)
    }
    return
  }
  if (!sameAttrs(current, target)) {
    tr.setNodeMarkup(start, undefined, target.attrs)
  }
  const delta = diffRich(richOf(current), richOf(target))
  let pos = start + 1
  for (const component of delta.components) {
    pos = applyComponent(tr, pos, component, target)
  }
}

function sameAttrs(a: PmNode, b: PmNode): boolean {
  return Object.keys(b.attrs).every((key) => a.attrs[key] === b.attrs[key])
}

/** 텍스트 편집의 조각 하나를 편집기 위치 pos 에 적용하고 다음 위치를 돌려준다. */
function applyComponent(tr: Transaction, pos: number, component: Component, target: PmNode): number {
  const schema = tr.doc.type.schema
  switch (component.kind) {
    case 'retain': {
      const end = pos + component.count
      for (const [key, value] of Object.entries(component.attrs)) {
        const type = schema.marks[key]
        if (type === undefined) {
          continue
        }
        tr.removeMark(pos, end, type)
        if (value !== null && value !== false) {
          tr.addMark(pos, end, markOf(type, key, value))
        }
      }
      return end
    }
    case 'delete':
      tr.delete(pos, pos + component.count)
      return pos
    case 'insert': {
      const marks = target.type.spec.marks === '' ? [] : marksOfAttrs(schema, component.attrs)
      tr.insert(pos, schema.text(component.text, marks))
      return pos + component.text.length
    }
  }
}

/**
 * 블록을 끌어 다른 블록의 앞이나 뒤에 놓는다 (E-5). 놓인 블록과 같은 부모 아래, 그 블록 바로 앞이나 바로 뒤로 옮긴다. 자식은 따라
 * 간다(부모 ID 가 트리를 나타낸다). 자기 자신이나 자기 하위 블록 옆에는 놓을 수 없고, 그 자리에 이미 있으면 할 일이 없다.
 */
export function moveBeside(view: Document, blockId: string, targetId: string, after: boolean): Intent | null {
  const block = view.get(blockId)
  const target = view.get(targetId)
  if (block === undefined || target === undefined || target.parentId === null || blockId === view.rootId) {
    return null
  }
  if (view.isSelfOrDescendant(targetId, blockId)) {
    return null
  }
  const siblings = view.children(target.parentId).filter((sibling) => sibling.id !== blockId)
  const index = siblings.findIndex((sibling) => sibling.id === targetId)
  const before = after ? (siblings[index] as Block) : siblings[index - 1]
  const next = after ? siblings[index + 1] : (siblings[index] as Block)
  const pos = between(before?.pos ?? null, next?.pos ?? null)
  if (block.parentId === target.parentId) {
    const current = view.children(target.parentId).findIndex((sibling) => sibling.id === blockId)
    const all = view.children(target.parentId)
    if (all[current - 1]?.id === (before?.id ?? undefined) && all[current + 1]?.id === (next?.id ?? undefined)) {
      return null
    }
  }
  return { ops: [moveBlock(blockId, target.parentId, pos)], cursor: { blockId, offset: 0 } }
}
