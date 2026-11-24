import { describe, expect, it } from 'vitest'
import * as A from '../ot/attrs'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { deleteBlock, insertBlock, mergeBlock, setProps, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { lostTexts, salvage } from './salvage'

const doc = Document.create('root').apply([
  insertBlock('X', 'root', 'V', 'paragraph', A.NO_ATTRS, RichText.of('가나다')),
  insertBlock('Y', 'root', 'W', 'paragraph', A.NO_ATTRS, RichText.of('라마')),
  insertBlock('Z', 'root', 'X', 'paragraph', A.NO_ATTRS, RichText.of('바사')),
])

const type = (blockId: string, offset: number, text: string): Op =>
  textOp(blockId, Delta.builder().retain(offset).insert(text).build())

describe('버려진 편집 살리기', () => {
  it('구조 편집이 섞인 블록은 잃고 다른 블록의 글과 속성 편집은 살린다', () => {
    const discarded = [type('Z', 2, '!'), mergeBlock('Y', 'X', 3), type('X', 0, '앞'), setProps('Z', 'level', 2)]

    const { kept, lost } = salvage(doc, [{ ops: discarded, against: null, blocked: new Set() }])

    expect(kept).toEqual([type('Z', 2, '!'), setProps('Z', 'level', 2)])
    expect(lost).toEqual([mergeBlock('Y', 'X', 3), type('X', 0, '앞')])
    expect(doc.apply(kept).get('Z')?.text.plainText()).toBe('바사!')
  })

  it('그 사이 확정된 다른 사람의 편집에 맞춰 위치를 옮긴다', () => {
    const remote = [type('Z', 0, '사람 ')]
    const view = doc.apply(remote)

    const { kept } = salvage(view, [{ ops: [type('Z', 2, '!')], against: remote, blocked: new Set() }])

    expect(view.apply(kept).get('Z')?.text.plainText()).toBe('사람 바사!')
  })

  it('같은 블록의 편집은 차례대로 묶어 함께 옮긴다', () => {
    const { kept } = salvage(doc, [{ ops: [type('Z', 2, 'ab'), type('Z', 4, 'c')], against: null, blocked: new Set() }])

    expect(doc.apply(kept).get('Z')?.text.plainText()).toBe('바사abc')
  })

  it('막힌 블록, 지운 블록, 새로 넣은 블록의 편집은 잃는다', () => {
    const view = doc.apply([deleteBlock('Y', true)])
    const discarded = [
      type('X', 0, '막힘'),
      type('Y', 0, '지움'),
      insertBlock('N', 'root', 'Y', 'paragraph', A.NO_ATTRS, RichText.of('새 블록')),
      type('N', 0, '더'),
      type('Z', 0, '살림'),
    ]

    const { kept, lost } = salvage(view, [{ ops: discarded, against: null, blocked: new Set(['X']) }])

    expect(kept).toEqual([type('Z', 0, '살림')])
    expect(lost).toHaveLength(4)
    expect(lostTexts(lost)).toEqual(['막힘', '지움', '새 블록', '더'])
  })

  it('적용할 수 없는 블록 편집은 그 블록의 편집을 통째로 잃는다', () => {
    const { kept, lost } = salvage(doc, [
      { ops: [type('Z', 1, 'a'), type('Z', 99, 'b'), type('X', 0, 'x')], against: null, blocked: new Set() },
    ])

    expect(kept).toEqual([type('X', 0, 'x')])
    expect(lost).toEqual([type('Z', 1, 'a'), type('Z', 99, 'b')])
  })
})
