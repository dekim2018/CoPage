import { describe, expect, it } from 'vitest'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { insertBlock } from '../ot/op'
import { RichText } from '../ot/richText'
import { moveBeside } from './model'

/** root 아래 a, b, c 가 차례로 있고 b 아래 b1 이 있다. */
function doc(): Document {
  const pa = between(null, null)
  const pb = between(pa, null)
  const pc = between(pb, null)
  return Document.create('root').apply([
    insertBlock('a', 'root', pa, 'paragraph', {}, RichText.of('a')),
    insertBlock('b', 'root', pb, 'paragraph', {}, RichText.of('b')),
    insertBlock('c', 'root', pc, 'paragraph', {}, RichText.of('c')),
    insertBlock('b1', 'b', between(null, null), 'paragraph', {}, RichText.of('b1')),
  ])
}

const order = (view: Document, parent: string) => view.children(parent).map((block) => block.id)

describe('블록 끌어 옮기기 (E-5)', () => {
  it('다른 블록의 뒤나 앞으로 옮기고 자식은 따라온다', () => {
    const view = doc()
    const after = moveBeside(view, 'b', 'c', true)
    expect(order(view.apply(after?.ops ?? []), 'root')).toEqual(['a', 'c', 'b'])
    expect(order(view.apply(after?.ops ?? []), 'b')).toEqual(['b1'])

    const before = moveBeside(view, 'c', 'a', false)
    expect(order(view.apply(before?.ops ?? []), 'root')).toEqual(['c', 'a', 'b'])
  })

  it('다른 부모의 블록 옆에 놓으면 그 부모 아래로 간다', () => {
    const view = doc()
    const intent = moveBeside(view, 'a', 'b1', true)
    const next = view.apply(intent?.ops ?? [])
    expect(order(next, 'b')).toEqual(['b1', 'a'])
    expect(order(next, 'root')).toEqual(['b', 'c'])
  })

  it('자기 하위 옆이나 이미 그 자리면 하지 않는다', () => {
    const view = doc()
    expect(moveBeside(view, 'b', 'b1', true)).toBeNull()
    expect(moveBeside(view, 'b', 'a', true)).toBeNull()
    expect(moveBeside(view, 'b', 'c', false)).toBeNull()
    expect(moveBeside(view, 'b', 'b', true)).toBeNull()
  })
})
