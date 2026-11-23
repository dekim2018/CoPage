import { Extension } from '@tiptap/core'
import type { Editor } from '@tiptap/core'
import { NodeSelection } from '@tiptap/pm/state'
import type { Transaction } from '@tiptap/pm/state'
import type { Document } from '../ot/document'
import type { Op } from '../ot/op'
import { PROP_DEFAULTS, SYNCED_PROPS, nodeName } from './blocks'
import type { BlockType } from './blocks'
import {
  REMOTE,
  indent,
  localOps,
  mergeBackward,
  mergeForward,
  moveTargets,
  newParagraphAfter,
  outdent,
  renderView,
  splitAt,
  toBlockPosition,
} from './model'
import type { BlockPosition, Display, Intent } from './model'
import { TOGGLE_EVENT } from './schema'

/** 편집기 바인딩이 협업 클라이언트에 바라는 것. 테스트에서는 가짜를 쓴다. */
export interface CollabTarget {
  view(): Document | null
  edit(ops: readonly Op[]): void
  hold(): void
  release(): void
}

/**
 * 편집기와 협업 클라이언트를 잇는다 (docs/10-FRONTEND.md 12.2).
 *
 * - 의도가 분명한 키(Enter, 블록 처음의 Backspace, 블록 끝의 Delete, Tab, Shift+Tab)는 가로채 split, merge, moveBlock 을 직접
 *   만든다
 * - 그 밖의 변화(타이핑, 붙여넣기, 한글 조합, 서식, 블록 종류 바꾸기, 할 일 체크, 마크다운 단축 입력)는 트랜잭션 전후를 블록 ID 로
 *   비교해 연산으로 만든다
 * - 남의 연산은 원격 표시를 단 트랜잭션으로 넣어 다시 연산으로 만들지 않는다
 * - 한글 조합 중에는 협업 클라이언트를 멈추고(hold) 편집기를 건드리지 않는다. 조합이 끝나면 쌓인 메시지를 처리하고 다시 그린다
 * - 토글의 접힘은 내 화면에만 적용한다. 문서에 저장하지 않는다
 */
export class EditorBinding {
  private composing = false
  private dirty = false
  private readonly collapsed = new Set<string>()

  constructor(
    private readonly editor: Editor,
    private readonly target: CollabTarget,
  ) {
    editor.on('transaction', ({ transaction }) => this.onTransaction(transaction))
    const dom = editor.view.dom
    dom.addEventListener('compositionstart', this.compositionStart)
    dom.addEventListener('compositionend', this.compositionEnd)
    dom.addEventListener(TOGGLE_EVENT, this.onToggle)
  }

  destroy(): void {
    const dom = this.editor.view.dom
    dom.removeEventListener('compositionstart', this.compositionStart)
    dom.removeEventListener('compositionend', this.compositionEnd)
    dom.removeEventListener(TOGGLE_EVENT, this.onToggle)
  }

  private display(): Display {
    return { collapsed: this.collapsed }
  }

  /** 화면 문서가 바뀌었다. 조합 중이면 미뤘다가 조합이 끝나면 그린다. */
  render(cursor?: BlockPosition): void {
    const view = this.target.view()
    if (view === null) {
      return
    }
    if (this.composing || this.editor.view.composing) {
      this.dirty = true
      return
    }
    this.dirty = false
    const tr = renderView(this.editor.state, view, cursor, this.display())
    if (tr !== null) {
      this.editor.view.dispatch(tr)
    }
  }

  /** 지금 커서가 있는 블록. 제목이면 루트 블록. */
  currentBlock(): BlockPosition | null {
    const selection = this.editor.state.selection
    if (selection instanceof NodeSelection) {
      const id = selection.node.attrs.blockId as string | null
      return id === null ? null : { blockId: id, offset: 0 }
    }
    return toBlockPosition(this.editor.state.doc, selection.from)
  }

  /**
   * 지금 블록의 종류를 바꾼다. 편집기 노드만 바꾸면 전후 비교가 setProps 로 만든다. 글이 있는 블록을 구분선으로 바꾸면 글은
   * 지워진다.
   */
  setBlockType(type: BlockType, props: Record<string, unknown> = {}): void {
    const { state } = this.editor
    const $from = state.selection.$from
    const index = $from.index(0)
    const node = state.doc.child(index)
    if (node.type.name === 'title') {
      return
    }
    let start = 0
    for (let i = 0; i < index; i++) {
      start += state.doc.child(i).nodeSize
    }
    const nodeType = state.schema.nodes[nodeName(type)]
    if (nodeType === undefined) {
      return
    }
    const attrs: Record<string, unknown> = { blockId: node.attrs.blockId, depth: node.attrs.depth }
    for (const key of SYNCED_PROPS[type]) {
      attrs[key] = props[key] ?? node.attrs[key] ?? PROP_DEFAULTS[key] ?? null
    }
    const tr = state.tr
    if (nodeType.isAtom || node.isAtom) {
      tr.replaceWith(start, start + node.nodeSize, nodeType.create(attrs))
    } else {
      if (nodeType.spec.marks === '') {
        tr.removeMark(start + 1, start + 1 + node.content.size)
      }
      tr.setNodeMarkup(start, nodeType, attrs)
    }
    this.editor.view.dispatch(tr)
    this.editor.view.focus()
  }

  enter(): boolean {
    const view = this.target.view()
    if (view === null || this.editor.view.composing) {
      return false
    }
    const { selection } = this.editor.state
    if (selection instanceof NodeSelection) {
      const id = selection.node.attrs.blockId as string | null
      return id === null ? true : this.apply(newParagraphAfter(view, id, this.display()))
    }
    // 코드 블록은 Enter 로 줄을 바꾼다. 편집기 기본 동작에 맡긴다.
    if (selection.$from.parent.type.spec.code === true) {
      return false
    }
    if (!selection.empty) {
      this.editor.commands.deleteSelection()
    }
    const position = toBlockPosition(this.editor.state.doc, this.editor.state.selection.from)
    if (position === null) {
      return true
    }
    const current = this.target.view() as Document
    const intent =
      position.blockId === current.rootId
        ? newParagraphAfter(current, null, this.display())
        : splitAt(current, position, this.display())
    return this.apply(intent)
  }

  backspace(): boolean {
    const selection = this.editor.state.selection
    const view = this.target.view()
    if (view === null || !selection.empty || this.editor.view.composing) {
      return false
    }
    const position = toBlockPosition(this.editor.state.doc, selection.from)
    if (position === null || position.offset > 0) {
      return false
    }
    if (position.blockId === view.rootId) {
      return true
    }
    const intent = mergeBackward(view, position.blockId, this.display())
    return intent === null ? true : this.apply(intent)
  }

  deleteForward(): boolean {
    const selection = this.editor.state.selection
    const view = this.target.view()
    if (view === null || !selection.empty || this.editor.view.composing) {
      return false
    }
    const position = toBlockPosition(this.editor.state.doc, selection.from)
    const block = position === null ? undefined : view.get(position.blockId)
    if (position === null || block === undefined || position.offset < block.text.length) {
      return false
    }
    if (position.blockId === view.rootId) {
      return true
    }
    const intent = mergeForward(view, position.blockId, this.display())
    return intent === null ? true : this.apply(intent)
  }

  /** Tab 은 들여쓰기, Shift+Tab 은 내어쓰기 (E-4). 코드 블록 안의 Tab 은 공백 두 칸이다. 포커스가 편집기를 떠나지 않게 늘 먹는다. */
  tab(out: boolean): boolean {
    const view = this.target.view()
    if (view === null || this.editor.view.composing) {
      return true
    }
    const { selection } = this.editor.state
    if (!out && selection.$from.parent.type.spec.code === true) {
      this.editor.commands.insertContent('  ')
      return true
    }
    const position = this.currentBlock()
    if (position === null || position.blockId === view.rootId) {
      return true
    }
    const intent = out ? outdent(view, position) : indent(view, position)
    if (intent !== null) {
      this.apply(intent)
    }
    return true
  }

  private apply(intent: Intent): boolean {
    // 내가 접힌 토글 아래로 블록을 옮기면 그 토글을 펼친다. 옮긴 블록이 숨어 사라진 것처럼 보이지 않게 한다.
    for (const parentId of moveTargets(intent.ops)) {
      this.collapsed.delete(parentId)
    }
    try {
      this.target.edit(intent.ops)
    } catch (e) {
      console.warn('편집을 적용하지 못해 화면을 다시 그린다', e)
    }
    this.render(intent.cursor)
    return true
  }

  private onTransaction(tr: Transaction): void {
    if (!tr.docChanged || tr.getMeta(REMOTE) === true) {
      return
    }
    const view = this.target.view()
    if (view === null) {
      return
    }
    let rerender = false
    try {
      const ops = localOps(view, tr.before, tr.doc, this.display())
      // 블록이 생기거나 사라지거나 종류가 바뀌면 다시 그려 ID, 깊이, 번호를 맞춘다.
      rerender = ops.some((op) => op.op !== 'text')
      this.target.edit(ops)
    } catch (e) {
      // 편집기와 화면 문서가 어긋났다. 화면 문서를 기준으로 다시 그린다.
      console.warn('편집을 연산으로 바꾸지 못해 화면을 다시 그린다', e)
      rerender = true
    }
    if (rerender) {
      // 트랜잭션 처리 중에 다시 보내지 않도록 한 박자 늦춘다.
      queueMicrotask(() => this.render())
    }
  }

  private readonly onToggle = (event: Event): void => {
    const blockId = (event as CustomEvent<{ blockId: string }>).detail.blockId
    if (this.collapsed.has(blockId)) {
      this.collapsed.delete(blockId)
    } else {
      this.collapsed.add(blockId)
    }
    this.render()
  }

  private readonly compositionStart = (): void => {
    this.composing = true
    this.target.hold()
  }

  private readonly compositionEnd = (): void => {
    // 편집기는 compositionend 뒤에 마지막 조합 결과를 트랜잭션으로 넣는다. 그 뒤에 풀어야 조합 결과가 한 묶음에 들어간다.
    setTimeout(() => {
      if (this.editor.isDestroyed || this.editor.view.composing) {
        return
      }
      this.composing = false
      this.target.release()
      if (this.dirty) {
        this.render()
      }
    }, 0)
  }
}

/** 가로채는 키를 바인딩으로 보낸다. 기본 키맵보다 먼저 받도록 우선순위를 높인다. */
export function keyboardExtension(binding: () => EditorBinding | null) {
  return Extension.create({
    name: 'copageKeys',
    priority: 1000,
    addKeyboardShortcuts() {
      return {
        Enter: () => binding()?.enter() ?? false,
        'Shift-Enter': () => binding()?.enter() ?? false,
        Backspace: () => binding()?.backspace() ?? false,
        Delete: () => binding()?.deleteForward() ?? false,
        Tab: () => binding()?.tab(false) ?? false,
        'Shift-Tab': () => binding()?.tab(true) ?? false,
      }
    },
  })
}
