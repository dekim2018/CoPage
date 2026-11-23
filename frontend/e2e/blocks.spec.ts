import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { caretAtEnd, controlInbox, newUser, open, sameShapes, selectText, shapes } from './helpers'

/* 블록 10종과 서식, 들여쓰기 (docs/01-PLAN.md E-1, E-2, E-4, E-7). 두 사람이 같은 문서를 연다. */

async function pair(browser: Parameters<typeof newUser>[0]) {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  return { a, b, docId }
}

/** 도구 막대로 지금 블록의 종류를 바꾼다. */
async function turnInto(page: Page, label: string): Promise<void> {
  await page.getByTestId('block-type').selectOption({ label })
}

test('마크다운 단축 입력과 도구 막대로 만든 블록 10종이 두 화면에 같게 보인다', async ({ browser }) => {
  const { a, b, docId } = await pair(browser)
  await Promise.all([open(a, docId), open(b, docId)])
  const k = a.keyboard

  await caretAtEnd(a, 0)
  await k.type('블록 모음')
  await k.press('Enter')
  await k.type('# 큰 제목')
  await k.press('Enter')
  await k.type('- 사과')
  await k.press('Enter')
  await k.type('배')
  await k.press('Enter')
  await k.press('Enter') // 빈 목록에서 Enter 는 문단으로 바뀐다
  await k.type('1. 하나')
  await k.press('Enter')
  await k.type('둘')
  await k.press('Enter')
  await k.press('Enter')
  await k.type('[] 장보기')
  await k.press('Enter')
  await k.press('Enter')
  await k.type('> 인용문')
  await k.press('Enter')
  await k.type('접는 제목')
  await turnInto(a, '토글')
  await caretAtEnd(a, -1)
  await k.press('Enter')
  await k.press('Enter')
  await k.type('알림')
  await turnInto(a, '콜아웃')
  await caretAtEnd(a, -1)
  await k.press('Enter')
  await k.type('---')
  await k.type('```')
  await k.type('let a = 1')

  const result = await sameShapes(a, b)
  expect(result.map(([kind, , text, extra]) => [kind, text, extra])).toEqual([
    ['title', '블록 모음', ''],
    ['heading', '큰 제목', '1'],
    ['bulleted', '사과', ''],
    ['bulleted', '배', ''],
    ['numbered', '하나', '1.'],
    ['numbered', '둘', '2.'],
    ['todo', '장보기', 'false'],
    ['quote', '인용문', ''],
    ['toggle', '▼접는 제목', ''],
    ['callout', '💡알림', ''],
    ['divider', '', ''],
    ['code', 'let a = 1', ''],
  ])
})

test('서식과 링크가 두 화면에 같게 보이고, 굵게 하는 동안 그 범위에 친 글자도 남는다', async ({ browser }) => {
  const { a, b, docId } = await pair(browser)
  const inboxA = await controlInbox(a)
  await Promise.all([open(a, docId), open(b, docId)])
  await caretAtEnd(a, 0)
  await a.keyboard.type('제목')
  await a.keyboard.press('Enter')
  await a.keyboard.type('가나다라')
  await sameShapes(a, b)

  // A 가 "나다" 를 굵게 하는 동안 B 가 그 사이(나와 다 사이)에 글자를 친다.
  inboxA.hold()
  await b.locator('.ProseMirror > *').nth(1).click()
  await b.keyboard.press('Home')
  await b.keyboard.press('ArrowRight')
  await b.keyboard.press('ArrowRight')
  await b.keyboard.type('X')
  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caretAtEnd(a, 1)
  await a.keyboard.press('ArrowLeft')
  await a.keyboard.press('Shift+ArrowLeft')
  await a.keyboard.press('Shift+ArrowLeft')
  await a.keyboard.press('ControlOrMeta+b')
  inboxA.release()
  await sameShapes(a, b)

  for (const page of [a, b]) {
    expect(await page.locator('.ProseMirror > *').nth(1).textContent()).toBe('가나X다라')
    expect(await page.locator('.ProseMirror strong').allTextContents()).toEqual(['나', '다'])
  }

  // 링크. "다라" 를 고른다.
  await selectText(a, 1, 3, 5)
  await a.getByTestId('link-input').fill('https://example.com')
  await a.getByTestId('link-apply').click()
  await expect(b.locator('.ProseMirror a[href="https://example.com"]')).toHaveText('다라')
  await a.getByTestId('link-input').fill('javascript:alert(1)')
  await a.getByTestId('link-apply').click()
  await expect(a.locator('.toolbar .error')).toBeVisible()
})

test('Tab 들여쓰기와 할 일 체크가 상대 화면에도 보인다', async ({ browser }) => {
  const { a, b, docId } = await pair(browser)
  await Promise.all([open(a, docId), open(b, docId)])
  await caretAtEnd(a, 0)
  await a.keyboard.type('목록')
  await a.keyboard.press('Enter')
  await a.keyboard.type('- 하나')
  await a.keyboard.press('Enter')
  await a.keyboard.type('둘')
  await a.keyboard.press('Tab')
  await a.keyboard.press('Enter')
  await a.keyboard.press('Enter') // 빈 목록 → 문단
  await a.keyboard.type('[] 할 일')

  let result = await sameShapes(a, b)
  expect(result.slice(1).map(([kind, depth, text]) => [kind, depth, text])).toEqual([
    ['bulleted', '0', '하나'],
    ['bulleted', '1', '둘'],
    ['todo', '1', '할 일'],
  ])

  await b.locator('.ProseMirror .todo input[type=checkbox]').click()
  result = await sameShapes(a, b)
  expect(result.at(-1)?.[3]).toBe('true')

  await caretAtEnd(a, 2)
  await a.keyboard.press('Shift+Tab')
  result = await sameShapes(a, b)
  expect(result.slice(1).map(([, depth]) => depth)).toEqual(['0', '0', '1'])
})

test('토글을 접으면 내 화면에서만 자식이 숨는다', async ({ browser }) => {
  const { a, b, docId } = await pair(browser)
  await Promise.all([open(a, docId), open(b, docId)])
  await caretAtEnd(a, 0)
  await a.keyboard.type('토글')
  await a.keyboard.press('Enter')
  await a.keyboard.type('펼치면 보임')
  await turnInto(a, '토글')
  await caretAtEnd(a, 1)
  // 토글에서 Enter 하면 다음 블록도 토글이다. 자식은 문단으로 바꾼다.
  await a.keyboard.press('Enter')
  await a.keyboard.type('안쪽 글')
  await a.keyboard.press('Tab')
  await turnInto(a, '문단')
  await sameShapes(a, b)

  await a.locator('.ProseMirror .toggle-button').click()

  await expect.poll(async () => (await shapes(a)).map(([, , text]) => text)).toEqual(['토글', '▶펼치면 보임'])
  expect((await shapes(b)).map(([, , text]) => text)).toEqual(['토글', '▼펼치면 보임', '안쪽 글'])
})
