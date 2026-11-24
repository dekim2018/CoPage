import { Extension } from '@tiptap/core'
import { Fragment, Slice } from '@tiptap/pm/model'
import type { Mark, Node as PmNode, Schema } from '@tiptap/pm/model'
import { Plugin, PluginKey } from '@tiptap/pm/state'

/**
 * 마크다운 글을 붙여넣으면 블록으로 바꾼다 (E-9, D-48). 다른 앱에서 복사한 일반 텍스트 중 마크다운처럼 보이는 것만 다룬다. 편집기
 * 안에서 복사한 것은 HTML 로 오므로 여기에 오지 않는다. 마크다운이 아니면 편집기의 기본 처리(줄마다 문단)에 맡긴다. 들여쓰기는
 * 읽지 않는다(붙인 블록의 깊이를 따른다).
 */

type BlockType = 'paragraph' | 'heading' | 'bulleted' | 'numbered' | 'todo' | 'quote' | 'codeBlock' | 'divider'

export interface InlineRun {
  text: string
  marks: { type: 'bold' | 'italic' | 'strike' | 'code' | 'link'; href?: string }[]
}

export interface MdBlock {
  type: BlockType
  attrs: Record<string, unknown>
  /** codeBlock 은 runs 하나에 글 그대로 */
  runs: InlineRun[]
}

const BLOCK_PATTERNS: [RegExp, (match: RegExpExecArray) => Omit<MdBlock, 'runs'> & { text: string }][] = [
  [/^(#{1,3})\s+(.*)$/, (m) => ({ type: 'heading', attrs: { level: (m[1] ?? '#').length }, text: m[2] ?? '' })],
  [/^[-*+]\s+\[( |x|X)\]\s+(.*)$/, (m) => ({ type: 'todo', attrs: { checked: m[1] !== ' ' }, text: m[2] ?? '' })],
  [/^[-*+]\s+(.*)$/, (m) => ({ type: 'bulleted', attrs: {}, text: m[1] ?? '' })],
  [/^\d{1,9}[.)]\s+(.*)$/, (m) => ({ type: 'numbered', attrs: {}, text: m[1] ?? '' })],
  [/^>\s?(.*)$/, (m) => ({ type: 'quote', attrs: {}, text: m[1] ?? '' })],
]
const DIVIDER = /^(-{3,}|\*{3,}|_{3,})$/
const FENCE = /^```/
const SAFE_URL = /^(https?:|mailto:)/i
const INLINE = /\*\*([^*]+)\*\*|~~([^~]+)~~|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])|(?<![\w])_([^_\s][^_]*)_(?![\w])/g

/** 한 줄 안의 굵게, 기울임, 취소선, 코드, 링크. */
export function parseInline(text: string): { runs: InlineRun[]; marked: boolean } {
  const runs: InlineRun[] = []
  let last = 0
  let marked = false
  for (const m of text.matchAll(INLINE)) {
    const at = m.index ?? 0
    if (at > last) {
      runs.push({ text: text.slice(last, at), marks: [] })
    }
    marked = true
    if (m[1] !== undefined) {
      runs.push({ text: m[1], marks: [{ type: 'bold' }] })
    } else if (m[2] !== undefined) {
      runs.push({ text: m[2], marks: [{ type: 'strike' }] })
    } else if (m[3] !== undefined) {
      runs.push({ text: m[3], marks: [{ type: 'code' }] })
    } else if (m[4] !== undefined) {
      // 서버와 편집기가 받는 주소만 링크로 둔다(docs/07-SECURITY.md 9.6). 아니면 글만 남긴다.
      runs.push({ text: m[4], marks: m[5] && SAFE_URL.test(m[5]) ? [{ type: 'link', href: m[5] }] : [] })
    } else {
      runs.push({ text: m[6] ?? m[7] ?? '', marks: [{ type: 'italic' }] })
    }
    last = at + m[0].length
  }
  if (last < text.length) {
    runs.push({ text: text.slice(last), marks: [] })
  }
  return { runs: runs.filter((run) => run.text !== ''), marked }
}

/** 마크다운 글을 블록으로 읽는다. 블록 문법도 서식도 없으면 null(기본 처리에 맡긴다). 빈 줄은 건너뛴다. */
export function parseMarkdown(text: string): MdBlock[] | null {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const blocks: MdBlock[] = []
  let markdown = false
  for (let i = 0; i < lines.length; i++) {
    const line = (lines[i] ?? '').trim()
    if (line === '') {
      continue
    }
    if (FENCE.test(line)) {
      const code: string[] = []
      i++
      while (i < lines.length && !FENCE.test((lines[i] ?? '').trim())) {
        code.push(lines[i] ?? '')
        i++
      }
      blocks.push({ type: 'codeBlock', attrs: {}, runs: code.length ? [{ text: code.join('\n'), marks: [] }] : [] })
      markdown = true
      continue
    }
    if (DIVIDER.test(line)) {
      blocks.push({ type: 'divider', attrs: {}, runs: [] })
      markdown = true
      continue
    }
    let block: MdBlock | null = null
    for (const [pattern, make] of BLOCK_PATTERNS) {
      const match = pattern.exec(line)
      if (match) {
        const { text: rest, ...head } = make(match)
        block = { ...head, runs: parseInline(rest).runs }
        markdown = true
        break
      }
    }
    if (block === null) {
      const inline = parseInline(line)
      markdown ||= inline.marked
      block = { type: 'paragraph', attrs: {}, runs: inline.runs }
    }
    blocks.push(block)
  }
  return markdown && blocks.length > 0 ? blocks : null
}

/** 읽은 블록을 편집기 노드로. 새 블록이라 blockId 는 비워 둔다(편집기 바인딩이 insertBlock 으로 만든다). */
export function toNodes(schema: Schema, blocks: MdBlock[], depth: number): PmNode[] {
  return blocks.map((block) => {
    const attrs = { ...block.attrs, depth }
    if (block.type === 'divider') {
      return schema.node('divider', attrs)
    }
    const content = block.runs.map((run) => {
      const marks: Mark[] =
        block.type === 'codeBlock'
          ? []
          : run.marks.map((mark) => schema.mark(mark.type, mark.href ? { href: mark.href } : undefined))
      return schema.text(run.text, marks)
    })
    return schema.node(block.type, attrs, content)
  })
}

export const MarkdownPaste = Extension.create({
  name: 'markdownPaste',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('markdownPaste'),
        props: {
          clipboardTextParser: (text, $context, plain) => {
            if (plain || $context.parent.type.spec.code) {
              return null as unknown as Slice
            }
            const blocks = parseMarkdown(text)
            if (blocks === null) {
              return null as unknown as Slice
            }
            const depth = Number($context.parent.attrs.depth ?? 0)
            // 닫힌 블록들로 넣는다. 붙인 자리의 블록은 나뉘고 그 사이에 들어간다.
            return new Slice(Fragment.fromArray(toNodes(this.editor.schema, blocks, depth)), 0, 0)
          },
        },
      }),
    ]
  },
})
