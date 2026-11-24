import { diffRich } from '../adapter/textDiff'
import * as A from '../ot/attrs'
import type { Document } from '../ot/document'
import { TYPE_KEY, deleteBlock, insertBlock, moveBlock, setProps, textOp } from '../ot/op'
import type { Op } from '../ot/op'

/**
 * 지금 문서를 과거 시점의 문서로 바꾸는 연산 묶음 (docs/08-FEATURES.md 10.1, V-2, D-44). 로그를 되감지 않고 보통 편집으로 보낸다.
 * 그래서 접속한 사람들의 화면이 평소처럼 바뀌고, 복원도 기록에 남고, 되돌리기(Ctrl+Z)로 되돌릴 수 있다.
 *
 * 블록 ID 로 맞춘다. 과거에만 보이는 블록은 다시 넣거나 삭제 표시를 지우고, 둘 다 있으면 자리, 종류, 속성, 글자의 차이를 연산으로
 * 만들고, 지금만 보이는 블록은 삭제 표시를 한다. 과거 트리를 위에서부터 차례로 맞춰 부모가 늘 먼저 제자리에 있다. 그래서 옮기는
 * 중에 고리가 생기지 않는다.
 */
export function restoreOps(now: Document, past: Document): Op[] {
  const ops: Op[] = []
  let current = now
  const push = (op: Op) => {
    ops.push(op)
    current = current.apply(op)
  }

  const align = (id: string) => {
    const target = past.get(id)
    const existing = current.get(id)
    if (target === undefined || existing === undefined) {
      return
    }
    if (existing.type !== target.type) {
      push(setProps(id, TYPE_KEY, target.type))
    }
    const keys = new Set([...Object.keys(existing.props), ...Object.keys(target.props)])
    for (const key of keys) {
      const after = target.props[key] ?? null
      if (!A.valueEquals(existing.props[key] ?? null, after)) {
        push(setProps(id, key, after))
      }
    }
    const delta = diffRich((current.get(id) as typeof existing).text, target.text)
    if (!delta.isNoop()) {
      push(textOp(id, delta))
    }
  }

  const visit = (parentId: string) => {
    for (const child of past.children(parentId)) {
      if (child.deleted) {
        continue
      }
      const existing = current.get(child.id)
      if (existing === undefined) {
        push(insertBlock(child.id, parentId, child.pos, child.type, child.props, child.text))
      } else {
        if (existing.deleted) {
          push(deleteBlock(child.id, false))
        }
        if (existing.parentId !== parentId || existing.pos !== child.pos) {
          push(moveBlock(child.id, parentId, child.pos))
        }
        align(child.id)
      }
      visit(child.id)
    }
  }

  align(past.rootId)
  visit(past.rootId)

  for (const block of [...current.blocks()]) {
    if (block.parentId === null || block.deleted || !current.isVisible(block.id)) {
      continue
    }
    if (past.get(block.id) === undefined || !past.isVisible(block.id)) {
      push(deleteBlock(block.id, true))
    }
  }
  return ops
}
