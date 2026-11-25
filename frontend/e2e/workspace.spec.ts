import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { caretAtEnd, newUser, open, sharedPage } from './helpers'

/** 사이드바의 검색(F-1, F-2), 페이지 끌어 옮기기(W-3), 프로필(A-3). D-47. */

/** 새 페이지를 만들고 제목과 문단 하나를 쓴다. 저장될 때까지 기다린다. */
async function newPage(page: Page, title: string, body: string): Promise<void> {
  const before = page.url()
  await page.getByRole('button', { name: '새 페이지' }).click()
  await page.waitForURL((url) => /\/p\/[0-9a-f-]{36}$/.test(url.pathname) && url.href !== before)
  await expect(page.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(page.locator('.ProseMirror[contenteditable=true]')).toBeVisible()
  await caretAtEnd(page, 0)
  await page.keyboard.type(title)
  if (body) {
    await page.keyboard.press('Enter')
    await page.keyboard.type(body)
  }
  await expect(page.getByTestId('status')).toHaveAttribute('data-status', 'saved')
}

test('사이드바에서 두 글자로 찾으면 제목과 본문에서 찾고, 누르면 그 페이지로 간다', async ({ browser }) => {
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
  await open(a, docId)
  await newPage(a, '주간 회의록', '다음 주 안건은 배포다')
  const url = a.url()
  await newPage(a, '다른 페이지', '')

  const search = a.getByTestId('search-input')
  await search.fill('회의')
  await expect(a.getByTestId('search-result')).toHaveCount(1)
  await expect(a.getByTestId('search-result')).toContainText('주간 회의록')
  // 본문은 편집이 멈추고 몇 초 뒤에 색인된다. 다시 찾아 본다.
  await expect(async () => {
    await search.fill('')
    await search.fill('안건')
    await expect(a.getByTestId('search-result')).toContainText('다음 주 안건', { timeout: 1_000 })
  }).toPass({ timeout: 15_000 })
  await a.getByTestId('search-result').click()
  await expect(a).toHaveURL(url)
  await expect(a.getByTestId('search-input')).toHaveValue('')

  await search.fill('없는말')
  await expect(a.getByText('찾은 페이지가 없다')).toBeVisible()
})

test('사이드바에서 페이지를 다른 페이지 위로 끌면 그 하위로 옮긴다', async ({ browser }) => {
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
  await open(a, docId)
  await newPage(a, '부모', '')
  await newPage(a, '옮길 것', '')
  const items = a.getByTestId('tree-item')
  await expect(items).toHaveCount(3)

  const moving = a.getByTestId('tree-row').filter({ hasText: '옮길 것' })
  const parent = a.getByTestId('tree-row').filter({ hasText: '부모' })
  await moving.dragTo(parent)

  // 부모가 펼쳐지고 그 아래에 보인다. 최상위에는 둘이 남는다.
  const under = a.getByTestId('tree-item').filter({ hasText: '부모' }).getByTestId('tree-item')
  await expect(under).toHaveCount(1)
  await expect(under).toContainText('옮길 것')
  await expect(a.locator('.sidebar > .tree > [data-testid=tree-item]')).toHaveCount(2)

  // 첫 줄의 위쪽 가장자리에 놓으면 맨 앞으로 간다 (D-50).
  await newPage(a, '맨 앞으로', '')
  const top = a.locator('.sidebar > .tree > [data-testid=tree-item]')
  await expect(top).toHaveCount(3)
  const firstRow = top.first().getByTestId('tree-row').first()
  const box = await firstRow.boundingBox()
  await a.getByTestId('tree-row').filter({ hasText: '맨 앞으로' }).dragTo(firstRow, {
    targetPosition: { x: 10, y: 2 },
  })
  await expect(top.first()).toContainText('맨 앞으로')
  expect(box).not.toBeNull()

  // 자기 하위로는 옮길 수 없다.
  await a.getByTestId('tree-row').filter({ hasText: '부모' }).first().dragTo(under.getByTestId('tree-row'))
  await expect(a.getByTestId('move-error')).toContainText('자기 하위로는 옮길 수 없다')
})

test('프로필에서 고른 커서 색이 상대의 접속자 목록에 보인다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await open(a, docId)
  await a.getByTestId('profile-link').click()
  await a.getByTestId('profile-name').fill('수정한 이름')
  await a.getByRole('button', { name: '#46a758' }).click()
  await a.getByTestId('profile-save').click()
  await expect(a.getByTestId('profile-saved')).toBeVisible()

  await Promise.all([open(a, docId), open(b, docId)])
  const peer = b.getByTestId('peers').locator('li[title="수정한 이름"]')
  await expect(peer).toHaveCSS('background-color', 'rgb(70, 167, 88)')
})

test('별을 누르면 사이드바의 즐겨찾기에 들어가고, 연 페이지는 최근 본 페이지에 최근부터 보인다', async ({ browser }) => {
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
  await open(a, docId)
  await newPage(a, '첫째 문서', '')
  await newPage(a, '둘째 문서', '')

  const recent = a.getByTestId('recent').getByRole('link')
  await expect(recent.first()).toHaveText('둘째 문서')
  await expect(recent.nth(1)).toHaveText('첫째 문서')

  await expect(a.getByTestId('favorites')).toHaveCount(0)
  await a.getByTestId('favorite-toggle').click()
  await expect(a.getByTestId('favorite-toggle')).toHaveAttribute('aria-pressed', 'true')
  await expect(a.getByTestId('favorites').getByRole('link')).toHaveText(['둘째 문서'])

  // 즐겨찾기를 눌러 다른 페이지로 갔다가 돌아오면 최근 순서가 바뀐다.
  await recent.nth(1).click()
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(recent.first()).toHaveText('첫째 문서')
  await a.getByTestId('favorites').getByRole('link').click()
  await expect(a.getByTestId('favorite-toggle')).toHaveAttribute('aria-pressed', 'true')
  await a.getByTestId('favorite-toggle').click()
  await expect(a.getByTestId('favorites')).toHaveCount(0)
})
