import { getSchema } from '@tiptap/core'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { describe, expect, it } from 'vitest'
import * as A from '../ot/attrs'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { TYPE_KEY, insertBlock, setProps, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import {
  flatten,
  indent,
  localOps,
  mergeBackward,
  moveTargets,
  outdent,
  readBlocks,
  renderView,
  splitAt,
  toBlockPosition,
  toPm,
  toPmPosition,
} from './model'
import type { Display } from './model'
import { schemaExtensions } from './schema'

const schema = getSchema(schemaExtensions)
const ROOT = 'root'

function doc(...ops: Op[]): Document {
  return Document.create(ROOT).apply(ops)
}

function block(id: string, pos: string, type: string, text = '', parent = ROOT, props: A.Attrs = A.NO_ATTRS): Op {
  return insertBlock(id, parent, pos, type, props, RichText.of(text))
}

/** 화면 순서대로 (ID, 깊이). */
function shape(view: Document, display?: Display): string[] {
  return flatten(view, display).map((f) => `${f.block.id}@${f.depth}`)
}

/** 편집기에서 한 변경을 흉내 내 연산을 만들고 적용한다. 연산이 서버 규칙(Document.apply)을 지키는지도 함께 본다. */
function edit(view: Document, change: (state: EditorState) => EditorState): { next: Document; ops: Op[] } {
  const before = EditorState.create({ schema, doc: toPm(schema, view) })
  const after = change(before)
  const ops = localOps(view, before.doc, after.doc)
  return { next: view.apply(ops), ops }
}

function nodeStart(state: EditorState, blockId: string): number {
  return (toPmPosition(state.doc, { blockId, offset: 0 }) as number) - 1
}

describe('블록 트리를 화면 순서로', () => {
  const tree = doc(
    block('A', 'a', 'toggle', '토글'),
    block('A1', 'a', 'paragraph', '안1', 'A'),
    block('A2', 'b', 'paragraph', '안2', 'A'),
    block('B', 'b', 'numbered', '하나'),
    block('C', 'c', 'numbered', '둘'),
    block('D', 'd', 'paragraph', '끊김'),
    block('E', 'e', 'numbered', '다시 하나'),
  )

  it('깊이 우선으로 늘어놓고 깊이를 단다', () => {
    expect(shape(tree)).toEqual(['A@0', 'A1@1', 'A2@1', 'B@0', 'C@0', 'D@0', 'E@0'])
  })

  it('번호 목록은 이어진 것끼리 번호를 매긴다', () => {
    const numbers = flatten(tree).filter((f) => f.block.type === 'numbered').map((f) => f.number)
    expect(numbers).toEqual([1, 2, 1])
  })

  it('접힌 토글의 자식은 늘어놓지 않는다', () => {
    expect(shape(tree, { collapsed: new Set(['A']) })).toEqual(['A@0', 'B@0', 'C@0', 'D@0', 'E@0'])
  })
})

describe('편집기 문서', () => {
  it('블록 종류, 속성, 서식을 오간다', () => {
    const view = doc(
      block('H', 'a', 'heading', '제목2', ROOT, A.of('level', 2)),
      block('T', 'b', 'todo', '할 일', ROOT, A.of('checked', true)),
      block('K', 'c', 'code', 'let a', ROOT, A.of('language', 'ts')),
      block('L', 'd', 'divider'),
      insertBlock('P', ROOT, 'e', 'paragraph', A.NO_ATTRS, RichText.of('굵게', A.copy({ bold: true, link: 'https://a.b' }))),
    )
    const blocks = readBlocks(toPm(schema, view)).slice(1)
    expect(blocks.map((b) => [b.type, b.props])).toEqual([
      ['heading', { level: 2 }],
      ['todo', { checked: true }],
      ['code', { language: 'ts' }],
      ['divider', {}],
      ['paragraph', {}],
    ])
    expect(blocks[4]?.text.segments).toEqual([{ text: '굵게', attrs: { bold: true, link: 'https://a.b' } }])
  })

  it('코드 블록은 서식을 그리지 않는다', () => {
    const view = doc(insertBlock('K', ROOT, 'a', 'code', A.NO_ATTRS, RichText.of('x', A.of('bold', true))))
    expect(readBlocks(toPm(schema, view))[1]?.text.segments).toEqual([{ text: 'x', attrs: {} }])
  })
})

describe('구분선과 위치', () => {
  it('구분선 아래 문단의 처음은 구분선이 아니라 그 문단이다', () => {
    const view = doc(block('P', 'a', 'paragraph', '위'), block('D', 'b', 'divider'), block('Q', 'c', 'paragraph', '아래'))
    const pm = toPm(schema, view)
    const start = toPmPosition(pm, { blockId: 'Q', offset: 0 }) as number

    expect(toBlockPosition(pm, start)).toEqual({ blockId: 'Q', offset: 0 })
    expect(toBlockPosition(pm, start + 1)).toEqual({ blockId: 'Q', offset: 1 })
    // 구분선 바로 앞(위 문단과의 경계)은 위 문단의 끝이다.
    const divider = toPmPosition(pm, { blockId: 'D', offset: 0 }) as number
    expect(toBlockPosition(pm, divider)).toEqual({ blockId: 'P', offset: 1 })
  })

  it('블록을 옮겨 넣는 부모를 알려 준다. 바인딩은 그 부모가 접혀 있으면 펼친다', () => {
    const view = doc(block('T', 'a', 'toggle', '토글'), block('P', 'b', 'paragraph', '다음'))
    expect(moveTargets(indent(view, { blockId: 'P', offset: 0 })?.ops as Op[])).toEqual(['T'])
  })
})

describe('편집기에서 연산으로', () => {
  const view = doc(block('X', 'a', 'paragraph', '가나다라'), block('Y', 'b', 'paragraph', '마바'))

  it('서식만 바꾸면 지우고 다시 넣지 않고 그 범위의 retain 이 된다', () => {
    const { ops, next } = edit(view, (s) => {
      const from = toPmPosition(s.doc, { blockId: 'X', offset: 1 }) as number
      return s.apply(s.tr.addMark(from, from + 2, schema.marks.bold!.create()))
    })
    expect(ops).toEqual([textOp('X', Delta.builder().retain(1).retain(2, A.of('bold', true)).build())])
    expect(next.get('X')?.text.segments.map((s) => s.attrs)).toEqual([{}, { bold: true }, {}])
  })

  it('서식을 끄면 null 로 지운다', () => {
    const bold = doc(insertBlock('X', ROOT, 'a', 'paragraph', A.NO_ATTRS, RichText.of('가나', A.of('bold', true))))
    const { ops } = edit(bold, (s) => {
      const from = toPmPosition(s.doc, { blockId: 'X', offset: 0 }) as number
      return s.apply(s.tr.removeMark(from, from + 2, schema.marks.bold!))
    })
    expect(ops).toEqual([textOp('X', Delta.builder().retain(2, A.of('bold', null)).build())])
  })

  it('블록 종류와 속성을 바꾸면 setProps 가 된다', () => {
    const { ops } = edit(view, (s) =>
      s.apply(s.tr.setNodeMarkup(nodeStart(s, 'X'), schema.nodes.todo, { blockId: 'X', depth: 0, checked: true })),
    )
    expect(ops).toEqual([setProps('X', TYPE_KEY, 'todo'), setProps('X', 'checked', true)])
  })

  it('지운 블록의 자식이 편집기에 남아 있으면 먼저 옮기고 지운다', () => {
    const tree = doc(block('P', 'a', 'toggle', '부모'), block('K', 'a', 'paragraph', '자식', 'P'))
    const { next } = edit(tree, (s) => {
      const start = nodeStart(s, 'P')
      return s.apply(s.tr.delete(start, start + (s.doc.nodeAt(start)?.nodeSize as number)))
    })
    expect(next.get('P')?.deleted).toBe(true)
    expect(shape(next)).toEqual(['K@0'])
  })

  it('자식이 펼쳐진 블록 바로 다음 줄에 생긴 블록은 첫 자식이 된다', () => {
    const tree = doc(block('P', 'a', 'toggle', '부모'), block('K', 'a', 'paragraph', '자식', 'P'))
    const { next } = edit(tree, (s) => {
      const afterP = nodeStart(s, 'K')
      return s.apply(s.tr.insert(afterP, schema.nodes.paragraph!.create(null, schema.text('새'))))
    })
    expect(flatten(next).map((f) => `${f.block.text.plainText()}@${f.depth}`)).toEqual(['부모@0', '새@1', '자식@1'])
  })
})

describe('가로채는 키', () => {
  it('목록에서 Enter 는 같은 종류로 이어 가고, 빈 목록에서 Enter 는 문단으로 바꾼다', () => {
    const view = doc(block('L', 'a', 'bulleted', '사과'), block('E', 'b', 'bulleted'))
    const split = view.apply(splitAt(view, { blockId: 'L', offset: 1 }).ops)
    expect(flatten(split).map((f) => [f.block.type, f.block.text.plainText()])).toEqual([
      ['bulleted', '사'],
      ['bulleted', '과'],
      ['bulleted', ''],
    ])
    expect(splitAt(view, { blockId: 'E', offset: 0 }).ops).toEqual([setProps('E', TYPE_KEY, 'paragraph')])
  })

  it('제목 블록에서 Enter 하면 뒤쪽은 문단이 된다', () => {
    const view = doc(block('H', 'a', 'heading', '큰제목', ROOT, A.of('level', 1)))
    const next = view.apply(splitAt(view, { blockId: 'H', offset: 1 }).ops)
    expect(flatten(next).map((f) => f.block.type)).toEqual(['heading', 'paragraph'])
  })

  it('자식이 있는 블록을 Backspace 로 합치면 자식을 먼저 옮긴다', () => {
    const view = doc(
      block('A', 'a', 'paragraph', '위'),
      block('B', 'b', 'paragraph', '아래'),
      block('B1', 'a', 'paragraph', '아래의 자식', 'B'),
    )
    const intent = mergeBackward(view, 'B')
    // 자식을 옮기지 않으면 Document.apply 가 합치기를 거부한다 (6.6).
    const next = view.apply(intent?.ops as Op[])
    expect(flatten(next).map((f) => `${f.block.text.plainText()}@${f.depth}`)).toEqual(['위아래@0', '아래의 자식@0'])
  })

  it('문단이 아닌 블록 처음의 Backspace 는 먼저 문단으로 바꾸고, 위가 구분선이면 구분선을 지운다', () => {
    const view = doc(block('D', 'a', 'divider'), block('Q', 'b', 'quote', '인용'), block('P', 'c', 'paragraph', '문단'))
    expect(mergeBackward(view, 'Q')?.ops).toEqual([setProps('Q', TYPE_KEY, 'paragraph')])
    const afterDivider = doc(block('D', 'a', 'divider'), block('P', 'b', 'paragraph', '문단'))
    expect(afterDivider.apply(mergeBackward(afterDivider, 'P')?.ops as Op[]).get('D')?.deleted).toBe(true)
  })

  it('Tab 은 앞 형제의 마지막 자식으로, Shift+Tab 은 부모 다음으로 옮기고 뒤 형제를 데려간다', () => {
    const view = doc(block('A', 'a', 'bulleted', 'a'), block('B', 'b', 'bulleted', 'b'), block('C', 'c', 'bulleted', 'c'))
    const indented = view.apply(indent(view, { blockId: 'B', offset: 0 })?.ops as Op[])
    const both = indented.apply(indent(indented, { blockId: 'C', offset: 0 })?.ops as Op[])
    expect(shape(both)).toEqual(['A@0', 'B@1', 'C@1'])
    expect(indent(view, { blockId: 'A', offset: 0 })).toBeNull()

    const out = both.apply(outdent(both, { blockId: 'B', offset: 0 })?.ops as Op[])
    // C 는 B 뒤에 있었으므로 B 의 자식으로 따라와 화면 순서가 그대로다.
    expect(shape(out)).toEqual(['A@0', 'B@0', 'C@1'])
    expect(outdent(view, { blockId: 'A', offset: 0 })).toBeNull()
  })
})

describe('연산에서 편집기로', () => {
  it('남이 내 커서가 든 범위를 굵게 하면 마크만 바뀌고 커서는 그대로다', () => {
    const view = doc(block('X', 'a', 'paragraph', '가나다라'))
    const pm = toPm(schema, view)
    const at = toPmPosition(pm, { blockId: 'X', offset: 2 }) as number
    const state = EditorState.create({ schema, doc: pm, selection: TextSelection.create(pm, at) })
    const remote = view.apply(textOp('X', Delta.builder().retain(1).retain(3, A.of('bold', true)).build()))

    const tr = renderView(state, remote)
    expect(tr?.mapping.map(at)).toBe(at)
    const next = state.apply(tr as never)
    expect(readBlocks(next.doc)[1]?.text.segments.map((s) => s.attrs)).toEqual([{}, { bold: true }])
    expect(toBlockPosition(next.doc, next.selection.head)).toEqual({ blockId: 'X', offset: 2 })
  })

  it('들여쓰기와 번호가 바뀌면 노드 속성만 바뀌고 커서는 그대로다', () => {
    const view = doc(block('A', 'a', 'numbered', 'a'), block('B', 'b', 'numbered', 'b'))
    const pm = toPm(schema, view)
    const at = toPmPosition(pm, { blockId: 'B', offset: 1 }) as number
    const state = EditorState.create({ schema, doc: pm, selection: TextSelection.create(pm, at) })
    const remote = view.apply(indent(view, { blockId: 'B', offset: 0 })?.ops as Op[])

    const next = state.apply(renderView(state, remote) as never)

    const b = next.doc.child(2)
    expect([b.attrs.depth, b.attrs.number]).toEqual([1, 1])
    expect(next.selection.head).toBe(at)
  })

  it('남이 블록 종류를 바꾸면 그 노드만 바뀐다', () => {
    const view = doc(block('X', 'a', 'paragraph', '가'), block('Y', 'b', 'paragraph', '나'))
    const state = EditorState.create({ schema, doc: toPm(schema, view) })
    const remote = view.apply([setProps('Y', TYPE_KEY, 'quote')])

    const next = state.apply(renderView(state, remote) as never)

    expect(readBlocks(next.doc).map((b) => b.type)).toEqual(['paragraph', 'paragraph', 'quote'])
  })
})
