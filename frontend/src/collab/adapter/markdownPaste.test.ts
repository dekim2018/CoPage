import { describe, expect, it } from 'vitest'
import { parseInline, parseMarkdown } from './markdownPaste'

describe('parseMarkdown', () => {
  it('블록 문법을 블록 종류로 읽고 빈 줄은 건너뛴다', () => {
    const blocks = parseMarkdown('# 제목\n\n## 소제목\n- 항목\n* [x] 끝난 일\n- [ ] 할 일\n1. 첫째\n> 인용\n---\n그냥 문단')
    expect(blocks?.map((b) => [b.type, b.attrs, b.runs.map((r) => r.text).join('')])).toEqual([
      ['heading', { level: 1 }, '제목'],
      ['heading', { level: 2 }, '소제목'],
      ['bulleted', {}, '항목'],
      ['todo', { checked: true }, '끝난 일'],
      ['todo', { checked: false }, '할 일'],
      ['numbered', {}, '첫째'],
      ['quote', {}, '인용'],
      ['divider', {}, ''],
      ['paragraph', {}, '그냥 문단'],
    ])
  })

  it('코드 울타리 안은 줄바꿈과 문법을 그대로 둔다', () => {
    const blocks = parseMarkdown('```ts\nconst a = 1\n# 주석 아님\n```\n다음')
    expect(blocks?.[0]).toEqual({ type: 'codeBlock', attrs: {}, runs: [{ text: 'const a = 1\n# 주석 아님', marks: [] }] })
    expect(blocks?.[1]?.type).toBe('paragraph')
  })

  it('마크다운이 아닌 여러 줄은 null 이라 기본 붙여넣기에 맡긴다', () => {
    expect(parseMarkdown('둘째\n셋째')).toBeNull()
    expect(parseMarkdown('')).toBeNull()
    expect(parseMarkdown('snake_case_name 과 2*3*4')).toBeNull()
  })

  it('서식만 있어도 마크다운으로 본다', () => {
    expect(parseMarkdown('이건 **굵게** 다')?.[0]?.runs).toEqual([
      { text: '이건 ', marks: [] },
      { text: '굵게', marks: [{ type: 'bold' }] },
      { text: ' 다', marks: [] },
    ])
  })
})

describe('parseInline', () => {
  it('굵게, 기울임, 취소선, 코드, 링크를 읽는다', () => {
    expect(parseInline('**a** *b* _c_ ~~d~~ `e` [f](https://x.example)').runs.filter((r) => r.marks.length)).toEqual([
      { text: 'a', marks: [{ type: 'bold' }] },
      { text: 'b', marks: [{ type: 'italic' }] },
      { text: 'c', marks: [{ type: 'italic' }] },
      { text: 'd', marks: [{ type: 'strike' }] },
      { text: 'e', marks: [{ type: 'code' }] },
      { text: 'f', marks: [{ type: 'link', href: 'https://x.example' }] },
    ])
  })

  it('받지 않는 주소의 링크는 글만 남긴다', () => {
    expect(parseInline('[누르기](javascript:alert(1))').runs[0]).toEqual({ text: '누르기', marks: [] })
  })
})
