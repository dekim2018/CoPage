import { getSchema } from '@tiptap/core'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { describe, expect, it } from 'vitest'
import * as A from '../ot/attrs'
import { Document } from '../ot/document'
import { insertBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { Delta } from '../ot/delta'
import { RichText } from '../ot/richText'
import {
  localOps,
  mergeBackward,
  mergeForward,
  readBlocks,
  renderView,
  splitAt,
  toBlockPosition,
  toPm,
  toPmPosition,
} from './model'
import { schemaExtensions } from './schema'
import { diffText } from './textDiff'

const schema = getSchema(schemaExtensions)
const ROOT = 'root'

/** 제목 "회의록" 아래 문단 X("가나다라"), Y("마바")가 차례로 있는 문서. */
function sample(): Document {
  return Document.create(ROOT).apply([
    textOp(ROOT, Delta.builder().insert('회의록').build()),
    insertBlock('X', ROOT, 'A', 'paragraph', A.NO_ATTRS, RichText.of('가나다라')),
    insertBlock('Y', ROOT, 'M', 'paragraph', A.NO_ATTRS, RichText.of('마바')),
  ])
}

function texts(doc: Document): string[] {
  return readBlocks(toPm(schema, doc)).map((b) => b.text)
}

/** 편집기에서 한 변경을 흉내 낸다. 변경 전후 편집기 문서로 연산을 만들어 화면 문서에 적용한다. */
function typeInEditor(view: Document, change: (state: EditorState) => EditorState): Document {
  const before = EditorState.create({ schema, doc: toPm(schema, view) })
  const after = change(before)
  const ops = localOps(view, before.doc, after.doc)
  return view.apply(ops)
}

describe('글자 차이', () => {
  it('앞뒤로 같은 부분을 남기고 가운데만 바꾼다', () => {
    expect(diffText('가나다라', '가나XY라').components).toEqual(Delta.builder().retain(2).delete(1).insert('XY').build().components)
    expect(diffText('abc', 'abc').isNoop()).toBe(true)
  })

  it('이모지의 가운데에서 자르지 않는다', () => {
    // 😀(D83D DE00)와 😁(D83D DE01)는 앞 코드 단위가 같다.
    const delta = diffText('a😀b', 'a😁b')
    expect(delta.apply(RichText.of('a😀b')).plainText()).toBe('a😁b')
    expect(delta.components[0]).toEqual({ kind: 'retain', count: 1, attrs: {} })
  })
})

describe('편집기 문서와 위치', () => {
  it('제목과 보이는 문단을 화면 순서대로 늘어놓는다', () => {
    const doc = sample().apply(insertBlock('Z', ROOT, 'B', 'paragraph', A.NO_ATTRS, RichText.of('끝')))
    expect(readBlocks(toPm(schema, doc))).toEqual([
      { id: ROOT, text: '회의록', title: true },
      { id: 'X', text: '가나다라', title: false },
      { id: 'Z', text: '끝', title: false },
      { id: 'Y', text: '마바', title: false },
    ])
  })

  it('편집기 위치와 블록 안 위치를 오간다', () => {
    const pm = toPm(schema, sample())
    // 제목(1 + 3 + 1) 다음 문단 X 의 여는 자리가 5, 첫 글자 앞이 6 이다.
    expect(toBlockPosition(pm, 8)).toEqual({ blockId: 'X', offset: 2 })
    expect(toPmPosition(pm, { blockId: 'X', offset: 2 })).toBe(8)
    expect(toPmPosition(pm, { blockId: 'Y', offset: 99 })).toBe(pm.content.size - 1)
  })
})

describe('편집기에서 연산으로', () => {
  it('타이핑은 그 블록의 text 하나가 된다', () => {
    const before = EditorState.create({ schema, doc: toPm(schema, sample()) })
    const after = before.apply(before.tr.insertText('!', 8))

    expect(localOps(sample(), before.doc, after.doc)).toEqual([
      textOp('X', Delta.builder().retain(2).insert('!').build()),
    ])
  })

  it('제목을 고치면 루트 블록의 text 가 된다', () => {
    const view = typeInEditor(sample(), (s) => s.apply(s.tr.insertText(' 초안', 4)))
    expect(texts(view)[0]).toBe('회의록 초안')
  })

  it('여러 블록에 걸쳐 지우면 사라진 블록은 deleteBlock, 남은 블록은 text 가 된다', () => {
    const view = typeInEditor(sample(), (s) => {
      const from = toPmPosition(s.doc, { blockId: 'X', offset: 2 }) as number
      const to = toPmPosition(s.doc, { blockId: 'Y', offset: 1 }) as number
      return s.apply(s.tr.delete(from, to))
    })
    expect(texts(view)).toEqual(['회의록', '가나바'])
    expect(view.get('Y')?.deleted).toBe(true)
  })

  it('ID 가 없는 새 노드는 insertBlock 이 되고 앞뒤 블록 사이에 놓인다', () => {
    const view = typeInEditor(sample(), (s) => {
      const afterX = (toPmPosition(s.doc, { blockId: 'X', offset: 4 }) as number) + 1
      return s.apply(s.tr.insert(afterX, schema.node('paragraph', null, [schema.text('새 줄')])))
    })
    expect(texts(view)).toEqual(['회의록', '가나다라', '새 줄', '마바'])
  })

  it('같은 ID 가 두 번 나오면 뒤의 것은 새 블록이다', () => {
    const view = typeInEditor(sample(), (s) => {
      const end = s.doc.content.size
      return s.apply(s.tr.insert(end, schema.node('paragraph', { blockId: 'X' }, [schema.text('복사')])))
    })
    expect(texts(view)).toEqual(['회의록', '가나다라', '마바', '복사'])
    expect(view.get('X')?.text.plainText()).toBe('가나다라')
  })
})

describe('Enter 와 Backspace', () => {
  it('Enter 는 커서에서 문단을 나누고 새 블록은 바로 뒤에 놓인다', () => {
    const view = sample()
    const { ops, cursor } = splitAt(view, { blockId: 'X', offset: 2 })
    const next = view.apply(ops)

    expect(texts(next)).toEqual(['회의록', '가나', '다라', '마바'])
    expect(next.get(cursor.blockId)?.text.plainText()).toBe('다라')
  })

  it('블록 처음의 Backspace 는 앞 문단에 합치고, 첫 문단이면 합치지 않는다', () => {
    const view = sample()
    const merged = mergeBackward(view, 'Y')
    expect(texts(view.apply(merged?.ops as Op[]))).toEqual(['회의록', '가나다라마바'])
    expect(merged?.cursor).toEqual({ blockId: 'X', offset: 4 })

    expect(mergeBackward(view, 'X')).toBeNull()
  })

  it('비어 있는 첫 문단에서 Backspace 하면 그 문단을 지우고 제목 끝으로 간다', () => {
    const view = Document.create(ROOT).apply(insertBlock('E', ROOT, 'A', 'paragraph'))
    const result = mergeBackward(view, 'E')

    expect(view.apply(result?.ops as Op[]).get('E')?.deleted).toBe(true)
    expect(result?.cursor).toEqual({ blockId: ROOT, offset: 0 })
  })

  it('블록 끝의 Delete 는 다음 문단을 끌어와 합친다', () => {
    const view = sample()
    const result = mergeForward(view, 'X')
    expect(texts(view.apply(result?.ops as Op[]))).toEqual(['회의록', '가나다라마바'])
  })
})

describe('연산에서 편집기로', () => {
  it('글만 바뀌면 그 부분만 바꾸고 내 커서는 편집기가 옮겨 준다', () => {
    const view = sample()
    const pm = toPm(schema, view)
    const cursor = toPmPosition(pm, { blockId: 'X', offset: 3 }) as number
    const state = EditorState.create({ schema, doc: pm, selection: TextSelection.create(pm, cursor) })
    // 다른 사람이 내 커서 앞에 두 글자를 넣었다.
    const remote = view.apply(textOp('X', Delta.builder().retain(1).insert('ab').build()))

    const next = state.apply(renderView(state, remote) as never)

    expect(readBlocks(next.doc)).toEqual(readBlocks(toPm(schema, remote)))
    expect(toBlockPosition(next.doc, next.selection.head)).toEqual({ blockId: 'X', offset: 5 })
  })

  it('블록이 생기거나 사라져도 내 커서는 같은 블록의 같은 자리에 남는다', () => {
    const view = sample()
    const pm = toPm(schema, view)
    const cursor = toPmPosition(pm, { blockId: 'Y', offset: 1 }) as number
    const state = EditorState.create({ schema, doc: pm, selection: TextSelection.create(pm, cursor) })
    const remote = view.apply(insertBlock('N', ROOT, 'B', 'paragraph', A.NO_ATTRS, RichText.of('끼어든 줄')))

    const next = state.apply(renderView(state, remote) as never)

    expect(readBlocks(next.doc).map((b) => b.id)).toEqual([ROOT, 'X', 'N', 'Y'])
    expect(toBlockPosition(next.doc, next.selection.head)).toEqual({ blockId: 'Y', offset: 1 })
  })

  it('바뀐 것이 없으면 트랜잭션을 만들지 않는다', () => {
    const state = EditorState.create({ schema, doc: toPm(schema, sample()) })
    expect(renderView(state, sample())).toBeNull()
  })
})
