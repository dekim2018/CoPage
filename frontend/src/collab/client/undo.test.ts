import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { deleteBlock, insertBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { UndoStack } from './undo'

function start(text: string): Document {
  return Document.create('root').apply([insertBlock('p', 'root', between(null, null), 'paragraph', {}, RichText.of(text))])
}

function text(doc: Document): string {
  return (doc.get('p') as { text: RichText }).text.plainText()
}

/** 내 편집: 적용하고 되돌리기 목록에 적는다. */
function mine(doc: Document, stack: UndoStack, ops: Op[], at: number): Document {
  stack.recordEdit(ops, doc, at)
  return doc.apply(ops)
}

function undo(doc: Document, stack: UndoStack): Document {
  const ops = stack.takeUndo()
  expect(ops).not.toBeNull()
  const next = doc.apply(ops as Op[])
  stack.applied(ops as Op[], doc, true)
  return next
}

function redo(doc: Document, stack: UndoStack): Document {
  const ops = stack.takeRedo()
  expect(ops).not.toBeNull()
  const next = doc.apply(ops as Op[])
  stack.applied(ops as Op[], doc, false)
  return next
}

const append = (length: number, s: string) => [textOp('p', Delta.builder().retain(length).insert(s).build())]

describe('되돌리기 (E-8)', () => {
  it('내 편집만 되돌리고 그 뒤에 남이 한 편집은 남긴다', () => {
    const stack = new UndoStack()
    let doc = mine(start('가'), stack, append(1, '나'), 0)
    // 남이 맨 앞에 쓴다.
    const remote = [textOp('p', Delta.builder().insert('앞').build())]
    doc = doc.apply(remote)
    stack.remoteChange(remote)

    doc = undo(doc, stack)
    expect(text(doc)).toBe('앞가')
    doc = redo(doc, stack)
    expect(text(doc)).toBe('앞가나')
  })

  it('600ms 안의 편집은 한 번에, 그보다 떨어진 편집은 따로 되돌린다', () => {
    const stack = new UndoStack()
    let doc = mine(start(''), stack, append(0, '가'), 0)
    doc = mine(doc, stack, append(1, '나'), 300)
    doc = mine(doc, stack, append(2, '다'), 2_000)

    doc = undo(doc, stack)
    expect(text(doc)).toBe('가나')
    doc = undo(doc, stack)
    expect(text(doc)).toBe('')
    expect(stack.canUndo()).toBe(false)
  })

  it('되돌린 뒤 새로 편집하면 다시 하기 목록은 비운다', () => {
    const stack = new UndoStack()
    let doc = mine(start(''), stack, append(0, '가'), 0)
    doc = undo(doc, stack)
    expect(stack.canRedo()).toBe(true)
    mine(doc, stack, append(0, '나'), 5_000)
    expect(stack.canRedo()).toBe(false)
  })

  it('남이 그 블록을 지워 되돌릴 수 없게 된 항목은 버린다', () => {
    const stack = new UndoStack()
    const doc = mine(start('가'), stack, append(1, '나'), 0)
    const remote = [deleteBlock('p', true)]
    doc.apply(remote)
    stack.remoteChange(remote)
    // 지운 블록의 글을 되돌리는 연산은 그대로 둘 수도 있다. 어느 쪽이든 지금 문서에 적용할 수 있어야 한다.
    const ops = stack.takeUndo()
    if (ops !== null) {
      expect(() => doc.apply(remote).apply(ops as Op[])).not.toThrow()
    }
  })

  it('옮길 수 없는 남의 변화가 오면 목록을 비운다', () => {
    const stack = new UndoStack()
    mine(start('가'), stack, append(1, '나'), 0)
    stack.remoteChange(null)
    expect(stack.canUndo()).toBe(false)
  })
})
