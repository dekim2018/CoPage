import type { Document } from '../ot/document'
import { TransformConflictError } from '../ot/errors'
import { invert } from '../ot/inverter'
import type { Op } from '../ot/op'
import { transform } from '../ot/transformer'

/** 잇따른 편집을 한 번의 되돌리기로 묶는 간격. 타이핑 한 덩어리가 한 번에 되돌아간다. */
export const GROUP_MS = 600

interface Entry {
  /** 지금 화면 문서에 그대로 적용하면 그 편집을 되돌리는 연산. 화면이 바뀔 때마다 그 변화에 맞춰 옮겨 둔다. */
  inverse: readonly Op[]
  at: number
}

/**
 * 되돌리기와 다시 하기 (E-8, docs/10-FRONTEND.md). 내가 한 편집만 되돌린다. 남이 그 뒤에 한 편집은 그대로 둔다.
 *
 * 항목마다 "지금 화면에 적용하면 그 편집을 되돌리는 연산" 을 든다. 화면이 바뀔 때마다(남의 편집, 내 새 편집, 되돌리기 자체) 모든
 * 항목을 그 변화에 맞춰 변환해 둔다. 그래서 어느 항목이든 언제나 지금 문서에 바로 적용할 수 있다. 변환할 수 없게 된
 * 항목(남이 그 블록을 지움 등)은 버린다.
 */
export class UndoStack {
  private undoEntries: Entry[] = []
  private redoEntries: Entry[] = []

  /** 내 편집을 적었다. before 는 그 편집을 적용하기 전 화면 문서다. 다시 하기 목록은 비운다. */
  recordEdit(ops: readonly Op[], before: Document, now: number): void {
    const inverse = invert(ops, before)
    this.redoEntries = []
    const top = this.undoEntries[this.undoEntries.length - 1]
    if (top !== undefined && now - top.at < GROUP_MS) {
      // 묶는다. 나중 편집을 먼저 되돌린다. 맨 위 항목은 이 편집 전 문서 기준으로 남겨야 이어 붙일 수 있어 옮기지 않는다.
      this.undoEntries = [
        ...this.moved(this.undoEntries.slice(0, -1), ops),
        { inverse: [...inverse, ...top.inverse], at: now },
      ]
      return
    }
    this.undoEntries = [...this.moved(this.undoEntries, ops), { inverse, at: now }]
  }

  /** 남의 편집이 화면을 바꿨다. null 이면 옮길 수 없는 변화라 목록을 비운다. */
  remoteChange(ops: readonly Op[] | null): void {
    if (ops === null) {
      this.clear()
      return
    }
    this.undoEntries = this.moved(this.undoEntries, ops)
    this.redoEntries = this.moved(this.redoEntries, ops)
  }

  /** 되돌릴 연산을 꺼낸다. 호출한 쪽이 화면에 적용한 뒤 applied 를 부른다. */
  takeUndo(): readonly Op[] | null {
    return this.undoEntries.pop()?.inverse ?? null
  }

  takeRedo(): readonly Op[] | null {
    return this.redoEntries.pop()?.inverse ?? null
  }

  /**
   * 되돌리기(또는 다시 하기)로 ops 를 적용했다. 나머지 항목을 옮기고, 반대쪽 목록에 이것을 다시 뒤집은 것을 넣는다.
   *
   * @param wasUndo 되돌리기였으면 다시 하기 목록에, 다시 하기였으면 되돌리기 목록에 넣는다
   */
  applied(ops: readonly Op[], before: Document, wasUndo: boolean): void {
    const inverse = invert(ops, before)
    this.undoEntries = this.moved(this.undoEntries, ops)
    this.redoEntries = this.moved(this.redoEntries, ops)
    // 되돌린 직후의 편집과 묶이지 않게 시각을 0 으로 둔다.
    const entry = { inverse, at: 0 }
    if (wasUndo) {
      this.redoEntries.push(entry)
    } else {
      this.undoEntries.push(entry)
    }
  }

  /** 지금 묶음을 닫는다. 다음 편집은 시간과 상관없이 새 되돌리기 단위가 된다(커서를 옮겨 다른 곳을 고칠 때). */
  closeGroup(): void {
    const top = this.undoEntries[this.undoEntries.length - 1]
    if (top !== undefined) {
      top.at = Number.NEGATIVE_INFINITY
    }
  }

  clear(): void {
    this.undoEntries = []
    this.redoEntries = []
  }

  canUndo(): boolean {
    return this.undoEntries.length > 0
  }

  canRedo(): boolean {
    return this.redoEntries.length > 0
  }

  /** 항목들을 화면의 변화 ops 에 맞춰 옮긴다. 옮길 수 없는 항목은 버린다. */
  private moved(entries: Entry[], ops: readonly Op[]): Entry[] {
    if (ops.length === 0) {
      return entries
    }
    const out: Entry[] = []
    for (const entry of entries) {
      try {
        const inverse = transform(entry.inverse, ops, true).a
        if (inverse.length > 0) {
          out.push({ inverse, at: entry.at })
        }
      } catch (e) {
        if (!(e instanceof TransformConflictError)) {
          throw e
        }
      }
    }
    return out
  }
}
