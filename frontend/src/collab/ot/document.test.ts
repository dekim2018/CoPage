import { describe, expect, it } from 'vitest'
import * as A from './attrs'
import { Document } from './document'
import type { Block } from './document'
import { between } from './fractionalIndex'
import { deleteBlock, insertBlock, moveBlock } from './op'
import { RichText } from './richText'

/** 자식 색인(PERF-1)이 예전처럼 블록 전체를 훑어 고른 결과와 같은지. */
function naiveChildren(doc: Document, parentId: string): string[] {
  return [...doc.blocks()]
    .filter((b) => b.parentId === parentId)
    .sort((a, b) => A.compareStrings(a.pos, b.pos) || A.compareStrings(a.id, b.id))
    .map((b) => b.id)
}

function ids(blocks: Block[]): string[] {
  return blocks.map((b) => b.id)
}

describe('문서의 자식 목록', () => {
  it('넣고 옮기고 지운 뒤에도 블록 전체를 훑어 고른 것과 같다', () => {
    let seed = 7
    const random = (n: number) => {
      seed = (seed * 1103515245 + 12345) % 2147483648
      return seed % n
    }
    let doc = Document.create('root')
    const created: string[] = ['root']
    for (let i = 0; i < 300; i++) {
      const roll = random(10)
      if (roll < 6 || created.length < 3) {
        const parent = created[random(created.length)] as string
        const siblings = doc.children(parent)
        const at = random(siblings.length + 1)
        const pos = between(siblings[at - 1]?.pos ?? null, siblings[at]?.pos ?? null)
        doc = doc.apply([insertBlock(`b${i}`, parent, pos, 'paragraph', A.NO_ATTRS, RichText.EMPTY)])
        created.push(`b${i}`)
      } else if (roll < 8) {
        const id = created[1 + random(created.length - 1)] as string
        const siblings = doc.children('root')
        try {
          doc = doc.apply([moveBlock(id, 'root', between(siblings[siblings.length - 1]?.pos ?? null, null))])
        } catch {
          // 이미 지운 블록이나 순환을 만드는 이동은 거부된다.
        }
      } else {
        const id = created[1 + random(created.length - 1)] as string
        try {
          doc = doc.apply([deleteBlock(id, true)])
        } catch {
          // 이미 지운 블록
        }
      }
      for (const parent of created) {
        expect(ids(doc.children(parent))).toEqual(naiveChildren(doc, parent))
      }
    }
  })

  it('돌려준 배열을 고쳐도 문서는 바뀌지 않는다', () => {
    const doc = Document.create('root').apply([
      insertBlock('a', 'root', 'V', 'paragraph', A.NO_ATTRS, RichText.EMPTY),
      insertBlock('b', 'root', 'W', 'paragraph', A.NO_ATTRS, RichText.EMPTY),
    ])
    const first = doc.children('root')
    first.reverse()
    first.pop()

    expect(ids(doc.children('root'))).toEqual(['a', 'b'])
    expect(doc.children('없는 블록')).toEqual([])
  })
})
