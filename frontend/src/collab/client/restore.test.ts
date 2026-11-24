import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { TYPE_KEY, deleteBlock, insertBlock, moveBlock, setProps, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { restoreOps } from './restore'

/** 보이는 블록을 트리 순서로 [id, 부모, 종류, 글] 로 늘어놓는다. 위치 키는 비교하지 않는다(순서로 본다). */
function shape(doc: Document): string[][] {
  const out: string[][] = [[doc.rootId, '', 'root', (doc.get(doc.rootId) as { text: RichText }).text.plainText()]]
  const walk = (parentId: string) => {
    for (const child of doc.children(parentId)) {
      if (!child.deleted) {
        out.push([child.id, parentId, child.type + JSON.stringify(child.props), child.text.plainText()])
        walk(child.id)
      }
    }
  }
  walk(doc.rootId)
  return out
}

describe('과거 시점으로 복원 (V-2)', () => {
  const p1 = between(null, null)
  const p2 = between(p1, null)
  const p3 = between(p2, null)
  const past = Document.create('root').apply([
    textOp('root', Delta.builder().insert('제목').build()),
    insertBlock('a', 'root', p1, 'heading', { level: 1 }, RichText.of('가')),
    insertBlock('b', 'root', p2, 'paragraph', {}, RichText.of('나')),
    insertBlock('b1', 'b', p1, 'paragraph', {}, RichText.of('자식')),
  ])

  it('글, 종류, 속성, 자리, 지운 블록, 새 블록을 모두 과거대로 돌린다', () => {
    const later: Op[] = [
      textOp('root', Delta.builder().retain(2).insert(' 바꿈').build()),
      setProps('a', TYPE_KEY, 'paragraph'),
      setProps('a', 'level', null),
      textOp('a', Delta.builder().insert('앞').build()),
      moveBlock('b1', 'root', p3),
      deleteBlock('b', true),
      insertBlock('c', 'root', between(p3, null), 'todo', { checked: true }, RichText.of('새것')),
    ]
    const now = past.apply(later)

    const ops = restoreOps(now, past)

    expect(shape(now.apply(ops))).toEqual(shape(past))
  })

  it('과거와 같으면 아무것도 하지 않는다', () => {
    expect(restoreOps(past, past)).toEqual([])
  })

  it('과거에 없던 블록 아래에 새로 쓴 블록도 지운다', () => {
    const now = past.apply([
      insertBlock('x', 'root', p3, 'paragraph', {}, RichText.of('x')),
      insertBlock('x1', 'x', p1, 'paragraph', {}, RichText.of('x1')),
    ])
    expect(shape(now.apply(restoreOps(now, past)))).toEqual(shape(past))
  })
})
