import { InputRule, Mark, Node } from '@tiptap/core'
import type { AnyExtension, NodeViewRendererProps } from '@tiptap/core'
import Bold from '@tiptap/extension-bold'
import Code from '@tiptap/extension-code'
import Document from '@tiptap/extension-document'
import Italic from '@tiptap/extension-italic'
import Link from '@tiptap/extension-link'
import Strike from '@tiptap/extension-strike'
import Text from '@tiptap/extension-text'
import Underline from '@tiptap/extension-underline'
import type { Attrs as PmAttrs, NodeType } from '@tiptap/pm/model'
import { TextSelection } from '@tiptap/pm/state'
import { MarkdownPaste } from './markdownPaste'

/*
 * 편집기 스키마. 블록 10종과 서식 6종 (docs/01-PLAN.md E-1, E-2).
 *
 * 편집기 문서는 제목 하나와 블록들이다. 블록은 중첩하지 않고 화면 순서대로 늘어놓으며, 트리에서의 깊이를 depth 속성으로 그린다.
 * 트리 모양은 문서의 부모 ID 가 나타낸다. 노드마다 blockId 로 어느 블록인지 적어 둔다. 편집기가 노드를 나눠도 ID 가 따라가지
 * 않고(keepOnSplit: false), 붙여넣은 HTML 의 ID 도 읽지 않는다. ID 가 없는 노드는 새 블록이다.
 *
 * number(번호 목록의 번호), collapsed 와 hasChildren(토글)은 화면에만 쓰는 속성이다. 문서에 저장하지 않는다.
 */

/** 토글의 접기 버튼을 누르면 편집기 DOM 에서 이 이벤트가 올라온다. 접힘은 내 화면에만 적용한다. */
export const TOGGLE_EVENT = 'copage-toggle'

const common = {
  blockId: {
    default: null,
    keepOnSplit: false,
    parseHTML: () => null,
    renderHTML: (attributes: Record<string, unknown>) =>
      attributes.blockId ? { 'data-block-id': attributes.blockId } : {},
  },
  depth: {
    default: 0,
    keepOnSplit: true,
    parseHTML: () => 0,
    renderHTML: (attributes: Record<string, unknown>) => ({
      'data-depth': attributes.depth,
      style: `margin-left: ${Number(attributes.depth) * 24}px`,
    }),
  },
}

const PageDocument = Document.extend({
  content: 'title block*',
})

const Title = Node.create({
  name: 'title',
  content: 'text*',
  marks: '',
  defining: true,
  addAttributes: () => ({ blockId: common.blockId }),
  parseHTML: () => [{ tag: 'h1' }],
  renderHTML: ({ HTMLAttributes }) => ['h1', { ...HTMLAttributes, class: 'page-title' }, 0],
})

/**
 * 블록 노드를 만든다. 마크다운 단축 입력(E-7)은 Tiptap 의 기본 입력 규칙 대신 아래 blockRule 을 쓴다. 기본 규칙은 노드 속성을
 * 새로 만들어 blockId 와 depth 를 잃는다.
 */
function block(
  name: string,
  spec: {
    tag: string
    className: string
    attrs?: Record<string, unknown>
    marks?: boolean
    code?: boolean
    rules?: (type: NodeType) => InputRule[]
    view?: (props: NodeViewRendererProps) => { dom: HTMLElement; contentDOM?: HTMLElement }
  },
) {
  return Node.create({
    name,
    group: 'block',
    content: 'text*',
    marks: spec.marks === false ? '' : '_',
    code: spec.code ?? false,
    defining: true,
    addAttributes: () => ({ ...common, ...(spec.attrs ?? {}) }),
    parseHTML: () => [{ tag: `${spec.tag}.${spec.className}` }],
    renderHTML: ({ HTMLAttributes }) => [spec.tag, { ...HTMLAttributes, class: `block ${spec.className}` }, 0],
    addInputRules() {
      return spec.rules?.(this.type) ?? []
    },
    ...(spec.view === undefined ? {} : { addNodeView: () => spec.view as never }),
  })
}

/** 블록 처음에 pattern 을 치면 그 블록을 type 으로 바꾼다. blockId 와 depth 는 그대로 둔다. 문단에서만 동작한다. */
function blockRule(find: RegExp, type: NodeType, attrs: PmAttrs = {}): InputRule {
  return new InputRule({
    find,
    handler: ({ state, range }) => {
      const $from = state.doc.resolve(range.from)
      const node = $from.parent
      if (node.type.name !== 'paragraph' || $from.parentOffset !== 0) {
        return null
      }
      const start = $from.before()
      state.tr.delete(range.from, range.to).setNodeMarkup(start, type, { ...node.attrs, ...attrs })
    },
  })
}

const label = (text: string) => {
  const span = document.createElement('span')
  span.contentEditable = 'false'
  span.className = 'block-label'
  span.textContent = text
  return span
}

const Paragraph = block('paragraph', { tag: 'p', className: 'paragraph' })

const Heading = block('heading', {
  tag: 'div',
  className: 'heading',
  attrs: {
    level: {
      default: 1,
      // 편집기 안에서 복사해 붙여넣을 때 그린 값을 다시 읽는다. blockId 와 depth 는 읽지 않는다(붙인 자리가 정한다).
      parseHTML: (element: HTMLElement) => {
        const level = Number(element.getAttribute('data-level'))
        return level >= 1 && level <= 3 ? level : 1
      },
      renderHTML: (attributes: Record<string, unknown>) => ({ 'data-level': attributes.level }),
    },
  },
  rules: (type) => [
    blockRule(/^###\s$/, type, { level: 3 }),
    blockRule(/^##\s$/, type, { level: 2 }),
    blockRule(/^#\s$/, type, { level: 1 }),
  ],
})

const Bulleted = block('bulleted', {
  tag: 'div',
  className: 'bulleted',
  rules: (type) => [blockRule(/^[-*]\s$/, type)],
})

const Numbered = block('numbered', {
  tag: 'div',
  className: 'numbered',
  attrs: {
    number: {
      default: 1,
      parseHTML: () => 1,
      renderHTML: (attributes: Record<string, unknown>) => ({ 'data-number': `${attributes.number}.` }),
    },
  },
  rules: (type) => [blockRule(/^1\.\s$/, type)],
})

const Todo = block('todo', {
  tag: 'div',
  className: 'todo',
  attrs: {
    checked: {
      default: false,
      parseHTML: (element: HTMLElement) => element.getAttribute('data-checked') === 'true',
      renderHTML: (attributes: Record<string, unknown>) => ({ 'data-checked': attributes.checked ? 'true' : 'false' }),
    },
  },
  rules: (type) => [blockRule(/^\[ ?\]\s$/, type, { checked: false })],
  view: ({ node, getPos, editor }) => {
    const dom = document.createElement('div')
    const checkbox = document.createElement('input')
    checkbox.type = 'checkbox'
    checkbox.contentEditable = 'false'
    const content = document.createElement('span')
    content.className = 'block-content'
    const sync = (current: typeof node) => {
      dom.className = `block todo${current.attrs.checked ? ' checked' : ''}`
      dom.style.marginLeft = `${Number(current.attrs.depth) * 24}px`
      dom.dataset.depth = String(current.attrs.depth)
      dom.dataset.blockId = String(current.attrs.blockId ?? '')
      checkbox.checked = Boolean(current.attrs.checked)
    }
    sync(node)
    checkbox.addEventListener('mousedown', (event) => event.preventDefault())
    checkbox.addEventListener('click', (event) => {
      event.preventDefault()
      const pos = typeof getPos === 'function' ? getPos() : undefined
      if (pos === undefined || !editor.isEditable) {
        return
      }
      const current = editor.state.doc.nodeAt(pos)
      if (current !== null) {
        editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, { ...current.attrs, checked: !current.attrs.checked }))
      }
    })
    dom.append(checkbox, content)
    return {
      dom,
      contentDOM: content,
      update: (updated: typeof node) => {
        if (updated.type !== node.type) {
          return false
        }
        sync(updated)
        return true
      },
    } as never
  },
})

const Toggle = block('toggle', {
  tag: 'div',
  className: 'toggle',
  attrs: {
    collapsed: { default: false, parseHTML: () => false, renderHTML: () => ({}) },
    hasChildren: { default: false, parseHTML: () => false, renderHTML: () => ({}) },
  },
  view: ({ node }) => {
    const dom = document.createElement('div')
    const button = document.createElement('button')
    button.type = 'button'
    button.contentEditable = 'false'
    button.className = 'toggle-button'
    const content = document.createElement('span')
    content.className = 'block-content'
    let blockId = node.attrs.blockId as string | null
    const sync = (current: typeof node) => {
      blockId = current.attrs.blockId as string | null
      dom.className = `block toggle${current.attrs.collapsed ? ' collapsed' : ''}`
      dom.style.marginLeft = `${Number(current.attrs.depth) * 24}px`
      dom.dataset.depth = String(current.attrs.depth)
      dom.dataset.blockId = String(blockId ?? '')
      button.textContent = current.attrs.collapsed ? '▶' : '▼'
      button.classList.toggle('empty', !current.attrs.hasChildren)
    }
    sync(node)
    button.addEventListener('mousedown', (event) => event.preventDefault())
    button.addEventListener('click', () => {
      if (blockId !== null) {
        dom.dispatchEvent(new CustomEvent(TOGGLE_EVENT, { bubbles: true, detail: { blockId } }))
      }
    })
    dom.append(button, content)
    return {
      dom,
      contentDOM: content,
      update: (updated: typeof node) => {
        if (updated.type !== node.type) {
          return false
        }
        sync(updated)
        return true
      },
    } as never
  },
})

const Quote = block('quote', { tag: 'div', className: 'quote', rules: (type) => [blockRule(/^>\s$/, type)] })

const Callout = block('callout', {
  tag: 'div',
  className: 'callout',
  view: ({ node }) => {
    const dom = document.createElement('div')
    const content = document.createElement('span')
    content.className = 'block-content'
    const sync = (current: typeof node) => {
      dom.className = 'block callout'
      dom.style.marginLeft = `${Number(current.attrs.depth) * 24}px`
      dom.dataset.depth = String(current.attrs.depth)
      dom.dataset.blockId = String(current.attrs.blockId ?? '')
    }
    sync(node)
    dom.append(label('💡'), content)
    return {
      dom,
      contentDOM: content,
      update: (updated: typeof node) => {
        if (updated.type !== node.type) {
          return false
        }
        sync(updated)
        return true
      },
    } as never
  },
})

const CodeBlock = block('codeBlock', {
  tag: 'pre',
  className: 'code',
  marks: false,
  code: true,
  attrs: {
    language: {
      default: null,
      parseHTML: (element: HTMLElement) => element.getAttribute('data-language'),
      renderHTML: (attributes: Record<string, unknown>) =>
        attributes.language ? { 'data-language': attributes.language } : {},
    },
  },
  rules: (type) => [blockRule(/^```$/, type)],
})

/** 구분선. 글이 없는 원자 노드다. 빈 문단에서 --- 를 치면 구분선으로 바꾸고 아래에 빈 문단을 둔다. */
const Divider = Node.create({
  name: 'divider',
  group: 'block',
  atom: true,
  selectable: true,
  addAttributes: () => ({ ...common }),
  parseHTML: () => [{ tag: 'hr' }],
  renderHTML: ({ HTMLAttributes }) => ['div', { ...HTMLAttributes, class: 'block divider' }, ['hr']],
  addInputRules() {
    const type = this.type
    return [
      new InputRule({
        find: /^---$/,
        handler: ({ state, range }) => {
          const $from = state.doc.resolve(range.from)
          const node = $from.parent
          // Tiptap 은 규칙을 부르기 전에 방금 친 글자를 문서에 먼저 넣는다. 넣기 전과 뒤 모두 맞도록 대시 두세 개뿐인지 본다.
          if (node.type.name !== 'paragraph' || $from.parentOffset !== 0 || !/^-{2,3}$/.test(node.textContent)) {
            return null
          }
          const start = $from.before()
          const tr = state.tr
          tr.delete(range.from, range.to).setNodeMarkup(start, type, { blockId: node.attrs.blockId, depth: node.attrs.depth })
          const after = start + 1
          tr.insert(after, state.schema.nodes.paragraph!.create({ depth: node.attrs.depth }))
          tr.setSelection(TextSelection.create(tr.doc, after + 1))
        },
      }),
    ]
  },
})

/** 편집기에 넣을 확장. 키 처리를 맡는 어댑터 확장은 따로 붙인다. */
/**
 * 댓글이 달린 글자 (C-1, C-2, docs/08-FEATURES.md 10.2). 값은 스레드 ID 다. 서식이라 글자가 밀리거나 블록이 나뉘어도 따라간다.
 * 같은 글자에 댓글이 겹치면 나중 것이 남는다(D-46).
 */
const CommentMark = Mark.create({
  name: 'comment',
  inclusive: false,
  addAttributes: () => ({ value: { default: null } }),
  parseHTML: () => [{ tag: 'span[data-comment]', getAttrs: (el) => ({ value: (el as HTMLElement).dataset.comment }) }],
  renderHTML: ({ HTMLAttributes }) => [
    'span',
    { class: 'comment-mark', 'data-comment': HTMLAttributes.value as string },
    0,
  ],
})

/** 멘션 (C-4). 값은 "멘션ID:사용자ID" 다. 서버가 커밋한 뒤 그 사용자에게 알린다. */
const MentionMark = Mark.create({
  name: 'mention',
  inclusive: false,
  addAttributes: () => ({ value: { default: null } }),
  parseHTML: () => [{ tag: 'span[data-mention]', getAttrs: (el) => ({ value: (el as HTMLElement).dataset.mention }) }],
  renderHTML: ({ HTMLAttributes }) => [
    'span',
    { class: 'mention-mark', 'data-mention': HTMLAttributes.value as string },
    0,
  ],
})

export const schemaExtensions: AnyExtension[] = [
  PageDocument,
  Title,
  Paragraph,
  Heading,
  Bulleted,
  Numbered,
  Todo,
  Toggle,
  Quote,
  Callout,
  CodeBlock,
  Divider,
  Text,
  Bold,
  Italic,
  Underline,
  Strike,
  Code,
  Link.configure({
    openOnClick: false,
    autolink: false,
    linkOnPaste: false,
    // 서버도 이 셋만 받는다 (docs/07-SECURITY.md 9.6).
    isAllowedUri: (url) => /^(https?:|mailto:)/i.test(url.trim()),
  }),
  CommentMark,
  MentionMark,
  MarkdownPaste,
]
