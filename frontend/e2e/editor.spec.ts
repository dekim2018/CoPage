import { expect, test } from '@playwright/test'
import { blocks, caret, caretAtEnd, controlInbox, converged, newUser, sharedPage, open, prepare } from './helpers'

/*
 * 브라우저 두 개로 같은 문서를 편집한다 (docs/12-ROADMAP.md 0단계 완료 기준, docs/11-TEST-PLAN.md 13.1 화면 테스트).
 * 두 사람은 서로 다른 사용자라 브라우저 컨텍스트(쿠키)도 따로다.
 */

test('두 사람이 같은 문단의 앞과 끝을 동시에 고치면 둘 다 반영된다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
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
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
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
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
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
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
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
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
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
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
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

test('홈의 새 문서는 페이지를 만들어 열고, 권한이 없는 페이지는 열지 못한다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  await a.getByRole('button', { name: '새 문서' }).click()
  await a.waitForURL(/\/p\/[0-9a-f-]{36}$/)
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  const pageId = a.url().split('/p/')[1]

  // b 는 그 워크스페이스에 속하지 않는다. 재접속을 이어 가지 않고 멈춘다.
  await b.goto(`/p/${pageId}`)
  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'error')
  await expect(b.getByTestId('status')).toContainText('404')
})
