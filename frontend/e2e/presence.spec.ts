import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { caret, caretAtEnd, controlInbox, converged, newUser, sharedPage, open } from './helpers'

/* 프레즌스: 접속자 목록과 남의 커서 (docs/05-PROTOCOL.md 7.6). */

async function peerNames(page: Page): Promise<string[]> {
  return page.getByTestId('peers').locator('li').allTextContents().then((names) => names.map((n) => n.trim()))
}

/** index 번째 블록 안에서 name 의 커서 앞에 있는 글. 커서가 그 블록에 없으면 null. */
async function textBeforeCaret(page: Page, index: number, name: string): Promise<string | null> {
  return page.locator('.ProseMirror > *').nth(index).evaluate((block, who) => {
    const caret = block.querySelector(`.remote-caret[data-name="${who}"]`)
    if (caret === null) {
      return null
    }
    const range = document.createRange()
    range.setStart(block, 0)
    range.setEndBefore(caret)
    return range.toString()
  }, name)
}

test('같은 문서를 연 사람의 이름이 접속자 목록에 보이고, 나가면 사라진다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, '민지'), newUser(browser, '준호')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])

  await expect.poll(() => peerNames(a)).toEqual(['준호'])
  await expect.poll(() => peerNames(b)).toEqual(['민지'])

  await a.close()
  await expect.poll(() => peerNames(b)).toEqual([])
})

test('남의 커서가 그 사람 이름과 함께 같은 자리에 보이고, 내가 앞에 글자를 치면 따라 밀린다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, '민지'), newUser(browser, '준호')])
  const docId = await sharedPage(a, b)
  const inboxA = await controlInbox(a)
  await Promise.all([open(a, docId), open(b, docId)])
  await caretAtEnd(a, 0)
  await a.keyboard.type('제목')
  await a.keyboard.press('Enter')
  await a.keyboard.type('가나다라')
  await converged(a, b)

  await caret(a, 1, 2)
  await expect.poll(() => textBeforeCaret(b, 1, '민지')).toBe('가나')

  // B 가 문단 맨 앞에 친다. B 화면의 민지 커서는 B 자신의 편집에 맞춰 뒤로 밀린다. A 는 그동안 B 의 편집을 받지 못하게 붙잡아
  // 둔다. 받으면 A 가 옮겨진 자기 커서를 다시 보내 주므로, B 쪽의 옮기기가 없어도 맞는 자리로 보여 확인이 되지 않는다.
  inboxA.hold()
  await caret(b, 1, 0)
  await b.keyboard.type('XY')
  await expect.poll(() => textBeforeCaret(b, 1, '민지')).toBe('XY가나')
  inboxA.release()
  // A 화면에도 B 의 커서가 B 가 친 글자 뒤에 보인다.
  await expect.poll(() => textBeforeCaret(a, 1, '준호')).toBe('XY')
})
