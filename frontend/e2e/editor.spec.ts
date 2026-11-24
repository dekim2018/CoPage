import { expect, test } from '@playwright/test'
import { apiAs, blocks, caret, caretAtEnd, controlInbox, converged, newUser, newWorkspaceWith, sharedPage, open, prepare } from './helpers'

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

test('64KB 를 넘는 붙여넣기는 여러 묶음으로 나눠 보내 끊기지 않고 상대에게 그대로 간다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['첫째'])

  // 한글 5만 자는 UTF-8 로 150KB 다. 묶음 하나로 보내면 서버가 4008 로 끊는다.
  const pasted = '가나다라마'.repeat(10_000)
  await caretAtEnd(a, 1)
  await a.locator('.ProseMirror').evaluate((editor, text) => {
    const data = new DataTransfer()
    data.setData('text/plain', text)
    editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  }, pasted)
  await a.keyboard.type('!')

  expect(await converged(a, b)).toEqual(['제목', `첫째${pasted}!`])
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
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

test('워크스페이스를 만들고 사이드바에서 페이지를 만들면 제목이 트리에 보이고, 남은 그 페이지를 열지 못한다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  await a.fill('input[name=workspace-name]', '우리 팀')
  await a.getByRole('button', { name: '워크스페이스 만들기' }).click()
  await a.waitForURL(/\/w\/[0-9a-f-]{36}$/)
  await a.getByRole('button', { name: '새 페이지' }).click()
  await a.waitForURL(/\/w\/[0-9a-f-]{36}\/p\/[0-9a-f-]{36}$/)
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caretAtEnd(a, 0)
  await a.keyboard.type('회의록')
  await expect(a.getByTestId('tree-item').first()).toContainText('회의록')

  // 하위 페이지를 만들면 부모가 펼쳐지고 경로에 부모가 보인다. 부모 제목의 마지막 커밋이 서버에 저장되기 전에 경로를 받아
  // 오면 예전 제목이 온다(CI 에서 "제목 없음" 으로 실패했다). 그 경우를 일부러 만든다: 경로의 제목을 비워 돌려준다.
  // 경로는 사이드바가 아는 최신 제목을 보여야 한다.
  await a.route(/\/api\/pages\/[0-9a-f-]{36}$/, async (route) => {
    const response = await route.fetch()
    const page = await response.json()
    for (const ancestor of page.path ?? []) {
      ancestor.title = ''
    }
    await route.fulfill({ response, json: page })
  })
  await a.getByTestId('tree-item').first().getByTitle('하위 페이지 만들기').click()
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect(a.getByTestId('crumbs')).toContainText('회의록')
  const pageId = a.url().split('/p/')[1]

  // b 는 그 워크스페이스에 속하지 않는다.
  await b.goto(`/p/${pageId}`)
  await expect(b.getByTestId('page-error')).toContainText('404')
})

test('남의 워크스페이스에 게스트로만 있으면 첫 화면에서 내 워크스페이스를 만든다', async ({ browser }) => {
  const [owner, guest] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'guest')])
  const theirs = await newWorkspaceWith(owner, guest, 'GUEST')

  await guest.goto('/')
  await expect(guest.getByRole('link', { name: '남의 팀' })).toBeVisible()
  await guest.fill('input[name=workspace-name]', '내 팀')
  await guest.getByRole('button', { name: '워크스페이스 만들기' }).click()
  await guest.waitForURL(/\/w\/[0-9a-f-]{36}$/)
  expect(guest.url()).not.toContain(theirs)
  await expect(guest.getByRole('button', { name: '새 페이지' })).toBeVisible()
})

test('공유 창으로 권한을 주고 내리면 상대 편집기에 1초 안에 적용된다', async ({ browser }) => {
  const [owner, guest] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'guest')])
  const pageId = await sharedPage(owner)
  const guestEmail = await guest.evaluate(async () => {
    const { accessToken } = (await (await fetch('/api/auth/refresh', { method: 'POST' })).json()) as {
      accessToken: string
    }
    const me = await fetch('/api/me', { headers: { Authorization: `Bearer ${accessToken}` } })
    return ((await me.json()) as { email: string }).email
  })
  await open(owner, pageId)
  await owner.getByRole('button', { name: '공유' }).click()
  const dialog = owner.getByTestId('share-dialog')
  await dialog.locator('input[name=share-email]').fill(guestEmail)
  await dialog.getByLabel('공유 등급').selectOption('EDIT')
  await dialog.getByRole('button', { name: '공유', exact: true }).click()
  await expect(dialog.locator(`li[data-email="${guestEmail}"]`)).toBeVisible()

  await open(guest, pageId)
  await dialog.locator(`li[data-email="${guestEmail}"]`).getByLabel('등급').selectOption('VIEW')
  await expect(guest.locator('.ProseMirror')).toHaveAttribute('contenteditable', 'false', { timeout: 1_000 })

  await dialog.locator(`li[data-email="${guestEmail}"]`).getByRole('button', { name: '회수' }).click()
  await expect(guest.getByTestId('status')).toHaveAttribute('data-status', 'error', { timeout: 1_000 })
})

test('OWNER 가 멤버 화면에서 사용자를 넣으면 그 사용자의 사이드바에 페이지가 보인다', async ({ browser }) => {
  const [owner, member] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'member')])
  const pageId = await sharedPage(owner)
  const memberEmail = await member.evaluate(async () => {
    const { accessToken } = (await (await fetch('/api/auth/refresh', { method: 'POST' })).json()) as {
      accessToken: string
    }
    const me = await fetch('/api/me', { headers: { Authorization: `Bearer ${accessToken}` } })
    return ((await me.json()) as { email: string }).email
  })
  await open(owner, pageId)
  await owner.getByRole('link', { name: '멤버' }).click()
  await owner.fill('input[name=member-email]', memberEmail)
  await owner.getByRole('button', { name: '넣기' }).click()
  await expect(owner.getByTestId('members').locator(`tr[data-email="${memberEmail}"]`)).toBeVisible()

  await member.goto('/')
  await member.waitForURL(/\/w\/[0-9a-f-]{36}$/)
  await expect(member.locator(`[data-testid=tree-item][data-page-id="${pageId}"]`)).toBeVisible()
})

test('권한이 내려가면 열린 편집기가 1초 안에 읽기 전용이 되고, 회수하면 닫힌다', async ({ browser }) => {
  const [owner, guest] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'guest')])
  const workspaceId = await newWorkspaceWith(owner, guest, 'GUEST')
  const { id: pageId } = await apiAs<{ id: string }>(owner, 'POST', `/api/workspaces/${workspaceId}/pages`, {})
  const { id: guestId } = await apiAs<{ id: string }>(guest, 'GET', '/api/me')
  await apiAs(owner, 'PUT', `/api/pages/${pageId}/grants/${guestId}`, { level: 'EDIT' })
  await open(guest, pageId)
  await expect(guest.getByTestId('readonly')).toHaveCount(0)

  await apiAs(owner, 'PUT', `/api/pages/${pageId}/grants/${guestId}`, { level: 'VIEW' })
  await expect(guest.locator('.ProseMirror')).toHaveAttribute('contenteditable', 'false', { timeout: 1_000 })
  await expect(guest.getByTestId('readonly')).toBeVisible()

  await apiAs(owner, 'DELETE', `/api/pages/${pageId}/grants/${guestId}`)
  await expect(guest.getByTestId('status')).toHaveAttribute('data-status', 'error', { timeout: 1_000 })
})

test('워크스페이스가 있어도 사이드바에서 둘째를 만들 수 있고, 속하지 않은 워크스페이스는 열 수 없다고 보인다', async ({
  browser,
}) => {
  const [a, stranger] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'stranger')])
  const pageId = await sharedPage(a)
  await open(a, pageId)
  const first = a.url().split('/w/')[1]?.split('/')[0] ?? ''

  await a.getByRole('link', { name: '워크스페이스 만들기' }).click()
  await a.fill('input[name=workspace-name]', '둘째 팀')
  await a.getByRole('button', { name: '워크스페이스 만들기' }).click()
  await a.waitForURL(/\/w\/[0-9a-f-]{36}$/)
  expect(a.url()).not.toContain(first)
  await expect(a.getByLabel('워크스페이스')).toContainText('둘째 팀')

  await stranger.goto(`/w/${first}`)
  await expect(stranger.getByTestId('page-error')).toHaveText('워크스페이스를 열 수 없다')
})

test('구조 충돌로 묶음이 거부되어도 다른 블록에 친 글자는 남고, 살리지 못한 편집은 보관함에 보인다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  const inboxB = await controlInbox(b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['', '둘째', '셋째'])

  // b 는 서버의 답을 받지 못한 채 편집한다. 첫 글자는 바로 보내지고, 그 뒤 편집은 한 묶음으로 쌓인다.
  inboxB.hold()
  await caretAtEnd(b, 3)
  await b.keyboard.type('x')
  await b.keyboard.type(' 살아남을 글')
  // 둘째 문단을 빈 첫 문단에 합친다. 같은 묶음에 들어간다.
  await caret(b, 2, 0)
  await b.keyboard.press('Backspace')
  // 그사이 a 는 빈 첫 문단을 지운다. b 의 합치기는 지워진 블록에 합치려는 충돌이라 거부된다.
  await caret(a, 1, 0)
  await a.keyboard.press('Backspace')
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  inboxB.release()

  expect(await converged(a, b)).toEqual(['제목', '둘째', '셋째x 살아남을 글'])
  await expect(b.getByTestId('lost')).toContainText('다른 사람의 편집과 겹쳐')
})

test('빠르게 쳐도(초당 50타) 묶음을 서버 한도 아래로 보내 연결이 끊기지 않는다', async ({ browser }) => {
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
  const sent: number[] = []
  const closes: number[] = []
  await a.routeWebSocket(/\/ws\/docs\//, (ws) => {
    const server = ws.connectToServer()
    ws.onMessage((message) => {
      if (String(message).includes('"t":"change"')) {
        sent.push(Date.now())
      }
      server.send(message)
    })
    server.onMessage((message) => ws.send(message))
    server.onClose((code) => {
      closes.push(code ?? 0)
    })
  })
  await open(a, docId)
  await caretAtEnd(a, 0)

  // 키를 누르고 있는 것과 비슷한 속도다. 답이 빠르면 예전에는 키마다 묶음이 나가 서버가 4008 로 끊었다.
  await a.keyboard.type('x'.repeat(150), { delay: 20 })

  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  let most = 0
  for (let i = 0; i < sent.length; i++) {
    let j = i
    while (j < sent.length && (sent[j] as number) - (sent[i] as number) < 1000) {
      j++
    }
    most = Math.max(most, j - i)
  }
  expect(closes).toEqual([])
  expect(most).toBeLessThanOrEqual(21)
  expect((await blocks(a))[0]).toBe('x'.repeat(150))
})
