import { Node } from '@tiptap/core'
import Document from '@tiptap/extension-document'
import Paragraph from '@tiptap/extension-paragraph'
import Text from '@tiptap/extension-text'
import type { AnyExtension } from '@tiptap/core'

/*
 * 편집기 스키마. 지금은 제목과 문단만 있다. 블록 10종과 서식은 다음 작업에서 넣는다.
 *
 * 편집기 문서는 제목 하나와 문단 여러 개다. 제목은 문서의 루트 블록, 문단은 루트 바로 아래의 블록이다. 노드마다 blockId 속성으로
 * 어느 블록인지 적어 둔다. 편집기가 노드를 나눠도 ID 가 따라가지 않게(keepOnSplit: false) 해서, ID 가 없는 노드는 새 블록으로
 * 본다.
 */

const blockId = {
  blockId: {
    default: null,
    keepOnSplit: false,
    // 붙여넣은 HTML 의 ID 는 읽지 않는다. 읽으면 복사한 블록과 붙여넣은 블록의 ID 가 같아진다. 붙여넣은 노드는 새 블록이다.
    parseHTML: () => null,
    renderHTML: (attributes: Record<string, unknown>) =>
      attributes.blockId ? { 'data-block-id': attributes.blockId } : {},
  },
}

const PageDocument = Document.extend({
  content: 'title paragraph*',
})

const Title = Node.create({
  name: 'title',
  content: 'text*',
  marks: '',
  defining: true,
  addAttributes() {
    return blockId
  },
  parseHTML() {
    return [{ tag: 'h1' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['h1', { ...HTMLAttributes, class: 'page-title' }, 0]
  },
})

const BlockParagraph = Paragraph.extend({
  marks: '',
  addAttributes() {
    return blockId
  },
})

/** 편집기에 넣을 확장. 키 처리를 맡는 어댑터 확장은 따로 붙인다. */
export const schemaExtensions: AnyExtension[] = [PageDocument, Title, BlockParagraph, Text]
