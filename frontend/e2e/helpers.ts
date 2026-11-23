import { expect } from '@playwright/test'
import type { Browser, Page } from '@playwright/test'

/* 화면 테스트가 함께 쓰는 도우미. */

const PASSWORD = 'correct horse 1'

export async function newUser(browser: Browser, name: string): Promise<Page> {
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
export interface Inbox {
  hold(): void
  release(): void
}

export async function controlInbox(page: Page): Promise<Inbox> {
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

export async function open(page: Page, docId: string): Promise<void> {
  await page.goto(`/p/${docId}`)
  await expect(page.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(page.locator('.ProseMirror[contenteditable=true]')).toBeVisible()
}

/** 편집기에 보이는 블록의 글. 첫 줄이 제목이다. */
export async function blocks(page: Page): Promise<string[]> {
  return page.locator('.ProseMirror > *').allTextContents()
}

/** 두 화면이 같은 내용이 되고 둘 다 보낼 것이 없을 때까지 기다린다. */
export async function converged(a: Page, b: Page): Promise<string[]> {
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
export async function caret(page: Page, index: number, offset: number): Promise<void> {
  await page.locator('.ProseMirror > *').nth(index).click()
  await page.keyboard.press('Home')
  for (let i = 0; i < offset; i++) {
    await page.keyboard.press('ArrowRight')
  }
}

/** index 번째 블록 끝에 커서를 놓는다. 음수면 뒤에서 센다. */
export async function caretAtEnd(page: Page, index: number): Promise<void> {
  await page.locator('.ProseMirror > *').nth(index).click()
  await page.keyboard.press('End')
}

/** 제목과 문단들을 써 두고 두 화면이 같아질 때까지 기다린다. */
export async function prepare(a: Page, b: Page, title: string, paragraphs: string[]): Promise<void> {
  await caretAtEnd(a, 0)
  await a.keyboard.type(title)
  for (const paragraph of paragraphs) {
    await a.keyboard.press('Enter')
    await a.keyboard.type(paragraph)
  }
  expect(await converged(a, b)).toEqual([title, ...paragraphs])
}

/** 블록의 모양. [종류, 깊이, 글, 덧붙임(제목 단계, 번호, 체크)] 으로 두 화면을 비교한다. */
export async function shapes(page: Page): Promise<string[][]> {
  return page.locator('.ProseMirror > *').evaluateAll((elements) =>
    elements.map((element) => {
      const el = element as HTMLElement
      const kind =
        ['heading', 'bulleted', 'numbered', 'todo', 'toggle', 'quote', 'callout', 'code', 'divider', 'paragraph'].find(
          (name) => el.classList.contains(name),
        ) ?? (el.classList.contains('page-title') ? 'title' : el.tagName.toLowerCase())
      const extra = el.dataset.level ?? el.dataset.number ?? (el.classList.contains('todo') ? String(el.classList.contains('checked')) : '')
      return [kind, el.dataset.depth ?? '', el.textContent ?? '', extra]
    }),
  )
}

/** 두 화면의 블록 모양이 같아지고 둘 다 보낼 것이 없을 때까지 기다린다. */
export async function sameShapes(a: Page, b: Page): Promise<string[][]> {
  await expect
    .poll(async () => {
      const saved =
        (await a.getByTestId('status').getAttribute('data-status')) === 'saved' &&
        (await b.getByTestId('status').getAttribute('data-status')) === 'saved'
      return saved && JSON.stringify(await shapes(a)) === JSON.stringify(await shapes(b))
    }, { timeout: 15_000 })
    .toBe(true)
  return shapes(a)
}

/**
 * index 번째 블록의 [from, to) 글자를 고른다. 서식이 있는 글에서는 브라우저가 서식 경계를 커서 칸 하나로 쳐서 Shift+화살표로
 * 고른 범위가 실행마다 달라지므로, 글자 위치로 직접 고른다.
 */
export async function selectText(page: Page, index: number, from: number, to: number): Promise<void> {
  await page.locator('.ProseMirror > *').nth(index).evaluate(
    (element, { start, end }) => {
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
      const range = document.createRange()
      let offset = 0
      let node: Node | null
      while ((node = walker.nextNode()) !== null) {
        const length = node.textContent?.length ?? 0
        if (start >= offset && start <= offset + length) {
          range.setStart(node, start - offset)
        }
        if (end >= offset && end <= offset + length) {
          range.setEnd(node, end - offset)
          break
        }
        offset += length
      }
      const selection = window.getSelection() as Selection
      selection.removeAllRanges()
      selection.addRange(range)
    },
    { start: from, end: to },
  )
  // 편집기는 selectionchange 를 받아 선택을 맞춘다.
  await expect.poll(() => page.evaluate(() => String(window.getSelection()))).not.toBe('')
}
