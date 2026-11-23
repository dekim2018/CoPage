import { Extension } from '@tiptap/core'
import type { Editor } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import type { Document } from '../ot/document'
import type { Op } from '../ot/op'
import {
  REMOTE,
  localOps,
  mergeBackward,
  mergeForward,
  newFirstParagraph,
  renderView,
  splitAt,
  toBlockPosition,
} from './model'
import type { BlockPosition } from './model'

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
 * - 의도가 분명한 키(Enter, 블록 처음의 Backspace, 블록 끝의 Delete)는 가로채 split, merge 를 직접 만든다
 * - 그 밖의 변화(타이핑, 붙여넣기, 한글 조합, 여러 블록에 걸친 선택 지우기)는 트랜잭션 전후를 블록 ID 로 비교해 연산으로 만든다
 * - 남의 연산은 원격 표시를 단 트랜잭션으로 넣어 다시 연산으로 만들지 않는다
 * - 한글 조합 중에는 협업 클라이언트를 멈추고(hold) 편집기를 건드리지 않는다. 조합이 끝나면 쌓인 메시지를 처리하고 다시 그린다
 */
export class EditorBinding {
  private composing = false
  private dirty = false

  constructor(
    private readonly editor: Editor,
    private readonly target: CollabTarget,
  ) {
    editor.on('transaction', ({ transaction }) => this.onTransaction(transaction))
    const dom = editor.view.dom
    dom.addEventListener('compositionstart', this.compositionStart)
    dom.addEventListener('compositionend', this.compositionEnd)
  }

  destroy(): void {
    const dom = this.editor.view.dom
    dom.removeEventListener('compositionstart', this.compositionStart)
    dom.removeEventListener('compositionend', this.compositionEnd)
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
    const tr = renderView(this.editor.state, view, cursor)
    if (tr !== null) {
      this.editor.view.dispatch(tr)
    }
  }

  enter(): boolean {
    const view = this.target.view()
    if (view === null || this.editor.view.composing) {
      return false
    }
    if (!this.editor.state.selection.empty) {
      this.editor.commands.deleteSelection()
    }
    const position = toBlockPosition(this.editor.state.doc, this.editor.state.selection.from)
    if (position === null) {
      return true
    }
    const current = this.target.view() as Document
    const intent = position.blockId === current.rootId ? newFirstParagraph(current) : splitAt(current, position)
    return this.apply(intent.ops, intent.cursor)
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
    const intent = mergeBackward(view, position.blockId)
    return intent === null ? true : this.apply(intent.ops, intent.cursor)
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
    const intent = mergeForward(view, position.blockId)
    return intent === null ? true : this.apply(intent.ops, intent.cursor)
  }

  private apply(ops: Op[], cursor: BlockPosition): boolean {
    try {
      this.target.edit(ops)
    } catch (e) {
      console.warn('편집을 적용하지 못해 화면을 다시 그린다', e)
    }
    this.render(cursor)
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
    let structural = false
    try {
      const ops = localOps(view, tr.before, tr.doc)
      structural = ops.some((op) => op.op !== 'text')
      this.target.edit(ops)
    } catch (e) {
      // 편집기와 화면 문서가 어긋났다. 화면 문서를 기준으로 다시 그린다.
      console.warn('편집을 연산으로 바꾸지 못해 화면을 다시 그린다', e)
      structural = true
    }
    if (structural) {
      // 새 블록에 ID 를 붙여 편집기 문서를 화면 문서와 맞춘다. 트랜잭션 처리 중에 다시 보내지 않도록 한 박자 늦춘다.
      queueMicrotask(() => this.render())
    }
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

/** Enter, Backspace, Delete 를 바인딩으로 보낸다. 기본 키맵보다 먼저 받도록 우선순위를 높인다. */
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
      }
    },
  })
}
