import type { BlockType } from './blocks'

/** 도구 막대와 슬래시 메뉴(E-6)가 함께 쓰는 블록 종류 목록. keywords 는 슬래시 뒤에 쳐서 찾는 말이다. */
export interface BlockChoice {
  label: string
  type: BlockType
  props?: Record<string, unknown>
  keywords: string[]
}

export const BLOCK_CHOICES: BlockChoice[] = [
  { label: '문단', type: 'paragraph', keywords: ['문단', 'text', 'paragraph', 'p'] },
  { label: '제목 1', type: 'heading', props: { level: 1 }, keywords: ['제목1', 'h1', 'heading1', '제목'] },
  { label: '제목 2', type: 'heading', props: { level: 2 }, keywords: ['제목2', 'h2', 'heading2', '제목'] },
  { label: '제목 3', type: 'heading', props: { level: 3 }, keywords: ['제목3', 'h3', 'heading3', '제목'] },
  { label: '글머리 기호 목록', type: 'bulleted', keywords: ['목록', '글머리', 'bullet', 'list', 'ul'] },
  { label: '번호 목록', type: 'numbered', keywords: ['번호', '목록', 'number', 'ol'] },
  { label: '할 일', type: 'todo', keywords: ['할일', 'todo', 'check', '체크'] },
  { label: '토글', type: 'toggle', keywords: ['토글', 'toggle'] },
  { label: '인용', type: 'quote', keywords: ['인용', 'quote'] },
  { label: '콜아웃', type: 'callout', keywords: ['콜아웃', 'callout'] },
  { label: '코드', type: 'code', keywords: ['코드', 'code'] },
  { label: '구분선', type: 'divider', keywords: ['구분선', 'divider', 'hr', 'line'] },
]

/** 슬래시 뒤의 말로 고른다. 이름이나 찾는 말이 그 말을 담으면 보인다. 빈 말이면 모두다. */
export function matchChoices(query: string): BlockChoice[] {
  const q = query.trim().toLowerCase()
  if (q === '') {
    return BLOCK_CHOICES
  }
  return BLOCK_CHOICES.filter(
    (choice) => choice.label.replace(/\s/g, '').includes(q) || choice.keywords.some((word) => word.includes(q)),
  )
}
