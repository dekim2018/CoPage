import { expect, test } from '@playwright/test'
import type { Browser, Page } from '@playwright/test'

/*
 * 브라우저 두 개로 같은 문서를 편집한다 (docs/12-ROADMAP.md 0단계 완료 기준, docs/11-TEST-PLAN.md 13.1 화면 테스트).
 * 두 사람은 서로 다른 사용자라 브라우저 컨텍스트(쿠키)도 따로다.
 */

const PASSWORD = 'correct horse 1'

async function newUser(browser: Browser, name: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage()
  await page.goto('/signup')
  await page.fill('input[name=email]', `${name}-${crypto.randomUUID()}@example.com`)
  await page.fill('input[name=password]', PASSWORD)
  await page.fill('input[name=name]', name)
  await page.click('button[type=submit]')
  await page.waitForURL('/')
  return page
}

/**
 * 서버에서 이 화면으로 오는 WebSocket 메시지를 잠시 붙잡는다. 붙잡은 동안 이 화면은 다른 사람의 편집을 모르고 편집한다. 그래야
 * 두 편집이 정말로 겹친다. 붙잡지 않으면 커서를 옮기는 사이 상대 편집이 먼저 도착해 겹침이 생기지 않는다.
 */
interface Inbox {
  hold(): void
  release(): void
}

async function controlInbox(page: Page): Promise<Inbox> {
  let held = false
  const queue: (() => void)[] = []
  await page.routeWebSocket(/\/ws\/docs\//, (ws) => {
    const server = ws.connectToServer()
    ws.onMessage((message) => server.send(message))
    server.onMessage((message) => {
      if (held) {
        queue.push(() => ws.send(message))
      } else {
        ws.send(message)
      }
    })
  })
  return {
    hold: () => {
      held = true
    },
    release: () => {
      held = false
      queue.splice(0).forEach((deliver) => deliver())
    },
  }
}

async function open(page: Page, docId: string): Promise<void> {
  await page.goto(`/p/${docId}`)
  await expect(page.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(page.locator('.ProseMirror[contenteditable=true]')).toBeVisible()
}

/** 편집기에 보이는 블록의 글. 첫 줄이 제목이다. */
async function blocks(page: Page): Promise<string[]> {
  return page.locator('.ProseMirror > *').allTextContents()
}

/** 두 화면이 같은 내용이 되고 둘 다 보낼 것이 없을 때까지 기다린다. */
async function converged(a: Page, b: Page): Promise<string[]> {
  await expect
    .poll(async () => {
      const [x, y] = [await blocks(a), await blocks(b)]
      const saved =
        (await a.getByTestId('status').getAttribute('data-status')) === 'saved' &&
        (await b.getByTestId('status').getAttribute('data-status')) === 'saved'
      return saved && JSON.stringify(x) === JSON.stringify(y)
    }, { timeout: 15_000 })
    .toBe(true)
  return blocks(a)
}

/** index 번째 블록(0 이 제목)의 offset 자리에 커서를 놓는다. */
async function caret(page: Page, index: number, offset: number): Promise<void> {
  await page.locator('.ProseMirror > *').nth(index).click()
  await page.keyboard.press('Home')
  for (let i = 0; i < offset; i++) {
    await page.keyboard.press('ArrowRight')
  }
}

async function caretAtEnd(page: Page, index: number): Promise<void> {
  await page.locator('.ProseMirror > *').nth(index).click()
  await page.keyboard.press('End')
}

/** 제목과 문단들을 써 두고 두 화면이 같아질 때까지 기다린다. */
async function prepare(a: Page, b: Page, title: string, paragraphs: string[]): Promise<void> {
  await caretAtEnd(a, 0)
  await a.keyboard.type(title)
  for (const paragraph of paragraphs) {
    await a.keyboard.press('Enter')
    await a.keyboard.type(paragraph)
  }
  expect(await converged(a, b)).toEqual([title, ...paragraphs])
}

test('두 사람이 같은 문단의 앞과 끝을 동시에 고치면 둘 다 반영된다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const inboxA = await controlInbox(a)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '회의록', ['본문'])

  inboxA.hold()
  await caretAtEnd(b, 1)
  await b.keyboard.type(' 끝')
  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caret(a, 1, 0)
  await a.keyboard.type('안녕 ')
  inboxA.release()

  expect(await converged(a, b)).toEqual(['회의록', '안녕 본문 끝'])
})

test('Enter 와 그 뒤쪽 타이핑이 겹치면 친 글자가 새 블록을 따라간다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const inboxA = await controlInbox(a)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['가나다라'])

  inboxA.hold()
  await caretAtEnd(b, 1)
  await b.keyboard.type('!')
  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caret(a, 1, 2)
  await a.keyboard.press('Enter')
  inboxA.release()

  expect(await converged(a, b)).toEqual(['제목', '가나', '다라!'])
})

test('Backspace 로 합치는 블록에 다른 사람이 치던 글자는 합쳐진 자리로 간다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const inboxA = await controlInbox(a)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['가나', '다라'])

  inboxA.hold()
  await caretAtEnd(b, 2)
  await b.keyboard.type('마')
  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caret(a, 2, 0)
  await a.keyboard.press('Backspace')
  inboxA.release()

  expect(await converged(a, b)).toEqual(['제목', '가나다라마'])
})

test('한글을 조합하는 동안 다른 사람이 편집해도 조합이 깨지지 않는다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['앞', '뒤'])

  // 브라우저의 입력기(IME) 조합을 Chrome DevTools Protocol 로 흉내 낸다. ㅎ → 하 → 한 → 한ㄱ → 한그 → 한글
  await caretAtEnd(a, 1)
  const ime = await a.context().newCDPSession(a)
  const steps = ['ㅎ', '하', '한', '한ㄱ', '한그', '한글']
  for (const [i, text] of steps.entries()) {
    await ime.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length })
    if (i === 2) {
      // 조합 한가운데에 다른 사람이 같은 문단과 다른 문단을 고친다.
      await caret(b, 1, 0)
      await b.keyboard.type('맨')
      await caretAtEnd(b, 2)
      await b.keyboard.type('쪽')
      await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'saved')
    }
  }
  await ime.send('Input.insertText', { text: '한글' })

  expect(await converged(a, b)).toEqual(['제목', '맨앞한글', '뒤쪽'])
})

test('여러 줄을 붙여넣은 뒤 이어 친 글자는 붙여넣은 글 끝에 들어간다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['첫째'])

  await caretAtEnd(a, 1)
  await a.locator('.ProseMirror').evaluate((editor) => {
    const data = new DataTransfer()
    data.setData('text/plain', '둘째\n셋째')
    editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  })
  await a.keyboard.type('!')

  expect(await converged(a, b)).toEqual(['제목', '첫째둘째', '셋째!'])
})

test('새로고침해도 로그인과 내용이 남는다', async ({ browser }) => {
  const docId = crypto.randomUUID()
  const a = await newUser(browser, 'a')
  await open(a, docId)
  await caretAtEnd(a, 0)
  await a.keyboard.type('남는 제목')
  await a.keyboard.press('Enter')
  await a.keyboard.type('남는 본문')
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')

  await a.reload()

  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  expect(await blocks(a)).toEqual(['남는 제목', '남는 본문'])
})
