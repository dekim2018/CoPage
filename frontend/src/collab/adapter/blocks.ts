import type { Mark, Node as PmNode, Schema } from '@tiptap/pm/model'
import * as A from '../ot/attrs'
import type { Attrs, AttrValue } from '../ot/attrs'
import { RichText, RichTextBuilder } from '../ot/richText'

/*
 * 블록 종류와 글자 서식 (docs/01-PLAN.md E-1, E-2, docs/04-OT-DESIGN.md 6.1). 편집기 노드 이름은 블록 종류와 같다. 다만 코드
 * 블록만 codeBlock 이다. 편집기는 노드와 마크(인라인 코드)가 같은 이름을 쓸 수 없다.
 */

export const BLOCK_TYPES = [
  'paragraph',
  'heading',
  'bulleted',
  'numbered',
  'todo',
  'toggle',
  'quote',
  'callout',
  'code',
  'divider',
] as const

export type BlockType = (typeof BLOCK_TYPES)[number]

export function isBlockType(type: string): type is BlockType {
  return (BLOCK_TYPES as readonly string[]).includes(type)
}

/** 블록 종류의 편집기 노드 이름. */
export function nodeName(type: BlockType): string {
  return type === 'code' ? 'codeBlock' : type
}

/** 편집기 노드 이름의 블록 종류. 제목 노드는 null. */
export function blockTypeOf(node: string): BlockType | null {
  if (node === 'codeBlock') {
    return 'code'
  }
  return isBlockType(node) && node !== 'code' ? node : null
}

/** 블록 속성 중 문서에 저장하고 맞추는 것. 편집기 노드의 같은 이름 속성과 짝이다. */
export const SYNCED_PROPS: Record<BlockType, readonly string[]> = {
  paragraph: [],
  heading: ['level'],
  bulleted: [],
  numbered: [],
  todo: ['checked'],
  toggle: [],
  quote: [],
  callout: [],
  code: ['language'],
  divider: [],
}

/** 저장된 속성이 없을 때 편집기 노드에 넣는 값. */
export const PROP_DEFAULTS: Record<string, AttrValue> = { level: 1, checked: false, language: null }

/** Enter 로 나눈 뒤쪽 블록의 종류. 목록과 할 일과 토글은 같은 종류로 이어 가고, 나머지는 문단이 된다. */
export function typeAfterSplit(type: string): BlockType {
  return type === 'bulleted' || type === 'numbered' || type === 'todo' || type === 'toggle' ? type : 'paragraph'
}

/** 글자 서식 6종. 문서의 서식 이름과 편집기 마크 이름이 같다. 링크만 값이 주소이고 나머지는 true 다. */
export const MARKS = ['bold', 'italic', 'underline', 'strike', 'code', 'link'] as const

/** 편집기 마크를 문서 서식으로. 모르는 마크는 버린다. */
export function attrsOfMarks(marks: readonly Mark[]): Attrs {
  const out: Record<string, AttrValue> = {}
  for (const mark of marks) {
    if (mark.type.name === 'link') {
      out.link = (mark.attrs.href as string | null) ?? null
    } else if ((MARKS as readonly string[]).includes(mark.type.name)) {
      out[mark.type.name] = true
    }
  }
  return A.copy(out)
}

/** 문서 서식을 편집기 마크로. 편집기에 없는 서식(댓글, 멘션 등)은 그리지 않는다. */
export function marksOfAttrs(schema: Schema, attrs: Attrs): Mark[] {
  let marks: readonly Mark[] = []
  for (const [key, value] of Object.entries(attrs)) {
    const type = schema.marks[key]
    if (type === undefined || value === null || value === false) {
      continue
    }
    const mark = key === 'link' ? type.create({ href: value }) : type.create()
    marks = mark.addToSet(marks)
  }
  return [...marks]
}

/** 편집기 노드의 글을 서식 있는 글로. */
export function richOf(node: PmNode): RichText {
  const builder = new RichTextBuilder()
  node.forEach((child) => {
    if (child.isText) {
      builder.append(child.text as string, attrsOfMarks(child.marks))
    }
  })
  return builder.build()
}

/** 서식 있는 글을 편집기 노드의 내용으로. allowMarks 가 false 면(코드 블록, 제목) 서식을 빼고 글만 넣는다. */
export function contentOf(schema: Schema, text: RichText, allowMarks: boolean): PmNode[] {
  return text.segments.map((s) => schema.text(s.text, allowMarks ? marksOfAttrs(schema, s.attrs) : []))
}
