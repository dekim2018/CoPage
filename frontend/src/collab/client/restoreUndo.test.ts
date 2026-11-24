import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { insertBlock, splitBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { restoreOps } from './restore'
import { UndoStack } from './undo'

describe('복원을 되돌린다', () => {
  it('복원 묶음의 역연산을 적용하면 복원 전으로 돌아간다', () => {
    const p1 = between(null, null)
    const past = Document.create('root').apply([
      textOp('root', Delta.builder().insert('제목').build()),
      insertBlock('a', 'root', p1, 'paragraph', {}, RichText.of('첫 판')),
    ])
    const later: Op[] = [
      textOp('a', Delta.builder().retain(3).insert(' 고침').build()),
      splitBlock('a', 6, 'b', 'root', between(p1, null), 'paragraph', {}),
      textOp('b', Delta.builder().insert('새 문단').build()),
    ]
    const now = past.apply(later)
    const stack = new UndoStack()
    const ops = restoreOps(now, past)
    stack.recordEdit(ops, now, 10_000)
    const restored = now.apply(ops)

    const inverse = stack.takeUndo() as Op[]
    expect(inverse).not.toBeNull()
    const visibleTexts = (doc: Document) =>
      [...doc.blocks()].filter((block) => doc.isVisible(block.id)).map((block) => block.text.plainText())
    expect(visibleTexts(restored.apply(inverse))).toEqual(visibleTexts(now))
  })
})
