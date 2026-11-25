import { expect, test } from '@playwright/test'
import type { Browser, Page } from '@playwright/test'
import { selectText } from '../e2e/helpers'

/* 시연 장면: 두 사람이 같은 페이지를 동시에 고치고, 댓글과 멘션 알림, 검색까지 (D-51). */

const SIZE = { width: 960, height: 640 }
const PASSWORD = 'correct horse 1'

async function person(browser: Browser, name: string, label: string): Promise<{ page: Page; email: string }> {
  const context = await browser.newContext({
    viewport: SIZE,
    recordVideo: { dir: `demo-results/video-${label}`, size: SIZE },
  })
  const page = await context.newPage()
  const email = `demo-${label}-${crypto.randomUUID()}@example.com`
  await page.goto('/signup')
  await page.fill('input[name=email]', email)
  await page.fill('input[name=password]', PASSWORD)
  await page.fill('input[name=name]', name)
  await page.click('button[type=submit]')
  await page.waitForURL('/')
  return { page, email }
}

/** 여름이 워크스페이스와 페이지를 만들고 겨울을 멤버로 넣는다. */
async function sharedPage(owner: Page, memberEmail: string): Promise<string> {
  return owner.evaluate(async (email) => {
    const { accessToken } = (await (await fetch('/api/auth/refresh', { method: 'POST' })).json()) as { accessToken: string }
    const call = async (path: string, body: unknown) =>
      (await (
        await fetch(path, {
          method: 'POST',
          headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        })
      ).json()) as { id: string }
    const workspace = await call('/api/workspaces', { name: '제품팀' })
    await call(`/api/workspaces/${workspace.id}/members`, { email, role: 'MEMBER' })
    return (await call(`/api/workspaces/${workspace.id}/pages`, {})).id
  }, memberEmail)
}

async function openDoc(page: Page, docId: string) {
  await page.goto(`/p/${docId}`)
  await expect(page.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(page.locator('.ProseMirror[contenteditable=true]')).toBeVisible()
}

async function endOf(page: Page, index: number) {
  await page.locator('.ProseMirror > *').nth(index).click()
  await page.keyboard.press('End')
}

test('시연: 동시 편집, 댓글과 멘션, 검색', async ({ browser }) => {
  const summer = await person(browser, '여름', 'a')
  const winter = await person(browser, '겨울', 'b')
  const a = summer.page
  const b = winter.page
  const docId = await sharedPage(a, winter.email)
  await Promise.all([openDoc(a, docId), openDoc(b, docId)])

  // 여름이 제목과 본문을 쓰는 동안 겨울도 같은 문단을 고친다. 두 화면이 같은 결과로 모인다.
  await endOf(a, 0)
  await a.keyboard.type('주간 회의록', { delay: 90 })
  await a.keyboard.press('Enter')
  await a.keyboard.type('배포 일정 정리', { delay: 90 })
  await a.keyboard.press('Enter')
  await a.keyboard.type('## 할 일 ', { delay: 90 })
  await a.keyboard.press('Enter')
  await a.keyboard.type('[] 검색 화면 점검', { delay: 90 })
  await endOf(b, 1)
  await Promise.all([
    b.keyboard.type(' (금요일 오후)', { delay: 110 }),
    (async () => {
      await endOf(a, 3)
      await a.keyboard.press('Enter')
      // 할 일에서 Enter 를 누르면 다음 줄도 할 일이다.
      await a.keyboard.type('알림 문구 다듬기', { delay: 110 })
    })(),
  ])
  await expect(b.locator('.ProseMirror')).toContainText('알림 문구 다듬기')
  await a.waitForTimeout(1_200)

  // 겨울이 글을 골라 댓글을 달고 여름을 멘션한다. 여름의 알림 종에 숫자가 오른다.
  await b.getByTestId('comments-button').click()
  await b.locator('.ProseMirror').focus()
  await selectText(b, 1, 0, 5)
  await b.getByTestId('comment-start').click()
  await b.getByTestId('comment-body').pressSequentially('@여름 금요일 괜찮아요?', { delay: 70 })
  await b.getByRole('button', { name: '달기' }).click()
  await expect(a.getByTestId('bell-count')).toHaveText('1')
  await a.waitForTimeout(800)
  await a.getByTestId('comments-button').click()
  await a.getByTestId('comment-reply').pressSequentially('네, 좋아요', { delay: 70 })
  await a.getByTestId('comment-reply').press('Enter')
  await expect(b.getByTestId('comment-thread')).toContainText('네, 좋아요')
  await a.waitForTimeout(1_000)

  // 여름이 사이드바에서 두 글자로 찾는다(본문은 몇 초 뒤 색인된다).
  await a.waitForTimeout(5_500)
  await a.getByTestId('search-input').pressSequentially('배포', { delay: 150 })
  await expect(a.getByTestId('search-result')).toContainText('주간 회의록')
  await a.waitForTimeout(2_000)
  await Promise.all([a.context().close(), b.context().close()])
})
