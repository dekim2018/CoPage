import * as A from './attrs'
import { Delta } from './delta'
import type { Block, Document } from './document'
import { InvalidArgumentError } from './errors'
import { assertNever, deleteBlock, mergeBlock, moveBlock, setProps, TYPE_KEY, textOp } from './op'
import type { MergeBlock, Op } from './op'

/*
 * 연산을 되돌리는 연산(역연산)을 만든다. 되돌리기(undo)에 쓴다. Java 의 `com.copage.ot.Inverter` 와 같은 규칙이다.
 *
 * 역연산을 적용하면 화면에 보이는 내용이 원래대로 돌아온다. 새로 만든 블록은 없애지 않고 삭제 표시만 하므로, 숨은 블록까지
 * 포함한 문서 전체가 완전히 같아지지는 않는다.
 */

/** before 문서에 op 를 적용했을 때, 그 결과를 되돌리는 연산들을 돌려준다. */
export function invertOp(op: Op, before: Document): Op[] {
  switch (op.op) {
    case 'text': {
      const inverse = op.delta.invert(blockOf(before, op.blockId).text)
      return inverse.isNoop() ? [] : [textOp(op.blockId, inverse)]
    }
    case 'split':
      return [mergeBlock(op.newBlockId, op.blockId, op.offset)]
    case 'merge':
      return invertMerge(op, before)
    case 'insertBlock':
      return [deleteBlock(op.blockId, true)]
    case 'deleteBlock':
      return [deleteBlock(op.blockId, blockOf(before, op.blockId).deleted)]
    case 'moveBlock': {
      const b = blockOf(before, op.blockId)
      return [moveBlock(b.id, b.parentId as string, b.pos)]
    }
    case 'setProps': {
      const b = blockOf(before, op.blockId)
      const previous = op.key === TYPE_KEY ? b.type : A.has(b.props, op.key) ? (b.props[op.key] ?? null) : null
      return [setProps(b.id, op.key, previous)]
    }
    default:
      return assertNever(op)
  }
}

/** 묶음 전체의 역연산. 뒤에서부터 하나씩 되돌린다. */
export function invert(ops: readonly Op[], before: Document): Op[] {
  const inverses: Op[][] = []
  let current = before
  for (const op of ops) {
    inverses.push(invertOp(op, current))
    current = current.apply(op)
  }
  return inverses.reverse().flat()
}

function invertMerge(merge: MergeBlock, before: Document): Op[] {
  const source = blockOf(before, merge.blockId)
  const out: Op[] = []
  if (!source.text.isEmpty()) {
    out.push(textOp(merge.intoId, Delta.builder().retain(merge.intoLength).delete(source.text.length).build()))
  }
  out.push(deleteBlock(source.id, false))
  if (!source.text.isEmpty()) {
    const restore = Delta.builder()
    for (const s of source.text.segments) {
      restore.insert(s.text, s.attrs)
    }
    out.push(textOp(source.id, restore.build()))
  }
  return out
}

function blockOf(doc: Document, blockId: string): Block {
  const b = doc.get(blockId)
  if (b === undefined) {
    throw new InvalidArgumentError(`블록 ${blockId} 이 없다`)
  }
  return b
}
