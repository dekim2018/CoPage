import { describe, expect, it } from 'vitest'
import * as A from './attrs'
import { Delta } from './delta'
import { Document } from './document'
import { InvalidOperationError } from './errors'
import { insertBlock, mergeBlock, splitBlock, textOp } from './op'
import type { Op } from './op'
import { RichText } from './richText'
import { transformOp } from './transformer'

/*
 * 설계 문서(docs/04-OT-DESIGN.md)의 규칙을 사람이 읽을 수 있는 사례로 적은 테스트. Java 의 ConcurrentEditExamplesTest 에서
 * 대표 사례를 골랐다. 두 구현이 같은지는 적합성 벡터(test/conformance.test.ts)가 확인한다.
 */

/** 루트 아래에 문단 X("가나다라")와 Y("마바")가 차례로 있는 문서. */
function sample(): Document {
  return Document.create('root').apply([
    insertBlock('X', 'root', 'A', 'paragraph', A.NO_ATTRS, RichText.of('가나다라')),
    insertBlock('Y', 'root', 'M', 'paragraph', A.NO_ATTRS, RichText.of('마바')),
  ])
}

function insertAt(blockId: string, offset: number, text: string, attrs: A.Attrs = A.NO_ATTRS): Op {
  return textOp(blockId, Delta.builder().retain(offset).insert(text, attrs).build())
}

function splitX(offset: number, newId: string): Op {
  return splitBlock('X', offset, newId, 'root', 'D', 'paragraph')
}

/** later 가 나중에 확정된다고 보고 두 순서의 결과가 같은지 확인한 뒤 그 문서를 돌려준다. */
function converge(start: Document, later: Op, earlier: Op): Document {
  const viaEarlier = start.apply(earlier).apply(transformOp(later, earlier, true))
  const viaLater = start.apply(later).apply(transformOp(earlier, later, false))
  expect(viaEarlier.equals(viaLater)).toBe(true)
  return viaEarlier
}

function text(doc: Document, blockId: string): string {
  return doc.get(blockId)?.text.plainText() ?? ''
}

describe('글자 위치', () => {
  it('UTF-16 코드 단위로 센다', () => {
    const doc = Document.create('root').apply(insertBlock('X', 'root', 'A', 'paragraph', A.NO_ATTRS, RichText.of('a😀b')))

    expect(doc.get('X')?.text.length).toBe(4)
    expect(text(doc.apply(insertAt('X', 3, '!')), 'X')).toBe('a😀!b')
  })

  it('이모지의 가운데를 가르는 편집과 나누기는 거부한다', () => {
    const doc = Document.create('root').apply(insertBlock('X', 'root', 'A', 'paragraph', A.NO_ATTRS, RichText.of('a😀b')))

    expect(() => doc.apply(insertAt('X', 2, '!'))).toThrow(InvalidOperationError)
    expect(() => doc.apply(splitX(2, 'N'))).toThrow(InvalidOperationError)
  })
})

describe('한쪽을 골라야 할 때', () => {
  it('같은 자리에 둘 다 글자를 넣으면 먼저 확정된 글자가 앞에 온다', () => {
    const result = converge(sample(), insertAt('X', 2, '늦'), insertAt('X', 2, '먼'))

    expect(text(result, 'X')).toBe('가나먼늦다라')
  })

  it('같은 글자의 같은 서식을 둘 다 바꾸면 나중에 확정된 값이 남는다', () => {
    const color = (value: string): Op => textOp('X', Delta.builder().retain(4, A.of('color', value)).build())

    const result = converge(sample(), color('blue'), color('red'))

    expect(result.get('X')?.text.segments).toEqual([{ text: '가나다라', attrs: { color: 'blue' } }])
  })
})

describe('Enter 와 Backspace 가 겹칠 때', () => {
  it('Enter 뒤쪽에 치던 글자는 새 블록을 따라간다', () => {
    const result = converge(sample(), insertAt('X', 3, '!'), splitX(2, 'N'))

    expect(text(result, 'X')).toBe('가나')
    expect(text(result, 'N')).toBe('다!라')
  })

  it('Enter 자리에 정확히 친 글자는 앞 블록 끝에 남는다', () => {
    const result = converge(sample(), insertAt('X', 2, '!'), splitX(2, 'N'))

    expect(text(result, 'X')).toBe('가나!')
    expect(text(result, 'N')).toBe('다라')
  })

  it('두 사람이 같은 자리에서 Enter 를 누르면 먼저 확정된 쪽이 뒤쪽 글을 갖고 나중 쪽은 빈 줄이 된다', () => {
    const result = converge(sample(), splitX(2, 'LATER'), splitX(2, 'EARLIER'))

    expect(text(result, 'X')).toBe('가나')
    expect(text(result, 'EARLIER')).toBe('다라')
    expect(text(result, 'LATER')).toBe('')
  })

  it('Backspace 로 합쳐진 블록에 치던 글자는 합쳐진 자리로 옮겨 간다', () => {
    const result = converge(sample(), insertAt('Y', 1, '!'), mergeBlock('Y', 'X', 4))

    expect(text(result, 'X')).toBe('가나다라마!바')
    expect(result.get('Y')?.deleted).toBe(true)
  })
})
