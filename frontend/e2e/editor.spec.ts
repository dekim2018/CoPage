import { expect, test } from '@playwright/test'
import {
  apiAs,
  blocks,
  caret,
  caretAtEnd,
  controlInbox,
  converged,
  newUser,
  newWorkspaceWith,
  open,
  prepare,
  shapes,
  sharedPage,
} from './helpers'

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

test('사이드바에서 휴지통으로 보내면 열어 둔 상대 편집기가 1초 안에 닫히고, 휴지통에서 꺼내면 돌아온다', async ({ browser }) => {
  const [owner, member] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'member')])
  const workspaceId = await newWorkspaceWith(owner, member, 'MEMBER')
  await owner.goto(`/w/${workspaceId}`)
  await owner.getByRole('button', { name: '새 페이지' }).click()
  await owner.waitForURL(/\/p\/[0-9a-f-]{36}$/)
  await expect(owner.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await caretAtEnd(owner, 0)
  await owner.keyboard.type('버릴 페이지')
  await expect(owner.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  const pageId = owner.url().split('/p/')[1] as string
  await open(member, pageId)

  const item = owner.locator(`[data-testid=tree-item][data-page-id="${pageId}"]`)
  await item.getByTitle('휴지통으로 보내기').click()
  await expect(item).toHaveCount(0)
  await owner.waitForURL(new RegExp(`/w/${workspaceId}$`))
  await expect(member.getByTestId('status')).toHaveAttribute('data-status', 'error', { timeout: 1_000 })

  await owner.getByRole('link', { name: '휴지통' }).click()
  const trashed = owner.locator(`[data-testid=trash-item][data-page-id="${pageId}"]`)
  await expect(trashed).toContainText('버릴 페이지')
  await trashed.getByRole('button', { name: '꺼내기' }).click()
  await owner.waitForURL(new RegExp(`/p/${pageId}$`))
  await expect(owner.locator(`[data-testid=tree-item][data-page-id="${pageId}"]`)).toContainText('버릴 페이지')
  await expect(owner.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect.poll(async () => (await blocks(owner))[0]).toBe('버릴 페이지')
})

test('열어 둔 하위 페이지의 조상을 휴지통으로 보내면 첫 화면으로 간다', async ({ browser }) => {
  const [owner, member] = await Promise.all([newUser(browser, 'owner'), newUser(browser, 'member')])
  const workspaceId = await newWorkspaceWith(owner, member, 'MEMBER')
  await owner.goto(`/w/${workspaceId}`)
  await owner.getByRole('button', { name: '새 페이지' }).click()
  await owner.waitForURL(/\/p\/[0-9a-f-]{36}$/)
  const parentId = owner.url().split('/p/')[1] as string
  const parent = owner.locator(`[data-testid=tree-item][data-page-id="${parentId}"]`)
  await parent.getByTitle('하위 페이지 만들기').click()
  await owner.waitForURL((url) => !url.pathname.endsWith(parentId))
  await expect(owner.getByTestId('crumbs')).toBeVisible()

  await parent.getByTitle('휴지통으로 보내기').first().click()

  await owner.waitForURL(new RegExp(`/w/${workspaceId}$`))
})

test('초대 링크를 받은 사람은 그 이메일로 가입하고 돌아와 워크스페이스에 참여한다', async ({ browser }) => {
  const owner = await newUser(browser, 'owner')
  await owner.fill('input[name=workspace-name]', '초대하는 팀')
  await owner.getByRole('button', { name: '워크스페이스 만들기' }).click()
  await owner.waitForURL(/\/w\/[0-9a-f-]{36}$/)
  const workspaceId = owner.url().split('/w/')[1] as string
  await owner.getByRole('link', { name: '멤버' }).click()
  const email = `invitee-${crypto.randomUUID()}@example.com`
  await owner.fill('input[name=invite-email]', email)
  await owner.getByRole('button', { name: '초대 링크 만들기' }).click()
  const link = await owner.getByTestId('invite-link').inputValue()
  await expect(owner.locator(`[data-testid=pending-invites] li[data-email="${email}"]`)).toBeVisible()

  // 로그인하지 않은 사람이 링크를 연다. 로그인 화면으로 갔다가 가입하고 초대 화면으로 돌아온다.
  const invitee = await (await browser.newContext()).newPage()
  await invitee.goto(new URL(link).pathname)
  await invitee.waitForURL((url) => url.pathname === '/login')
  await invitee.getByRole('link', { name: '가입하기' }).click()
  // 로그인 화면에도 같은 이름의 입력란이 있어, 가입 화면이 뜬 뒤에 채운다.
  await invitee.waitForURL((url) => url.pathname === '/signup')
  await invitee.fill('input[name=email]', email)
  await invitee.fill('input[name=password]', 'correct horse 1')
  await invitee.fill('input[name=name]', '초대받은 사람')
  await invitee.click('button[type=submit]')
  // next 쿼리에도 /invite/ 가 들어 있어 경로만 본다.
  await invitee.waitForURL((url) => url.pathname.startsWith('/invite/'))
  await expect(invitee.getByTestId('invite')).toContainText('초대하는 팀')
  await invitee.getByRole('button', { name: '참여하기' }).click()

  await invitee.waitForURL(new RegExp(`/w/${workspaceId}$`))
  await expect(invitee.getByLabel('워크스페이스')).toContainText('초대하는 팀')
  await owner.reload()
  await expect(owner.locator(`[data-testid=members] tr[data-email="${email}"]`)).toContainText('MEMBER')
  await expect(owner.locator(`[data-testid=pending-invites] li`)).toHaveCount(0)
})

test('공유 링크로 로그인하지 않은 사람이 페이지를 보고, 등급을 올리면 편집하고, 링크를 끄면 1초 안에 닫힌다', async ({ browser }) => {
  const owner = await newUser(browser, 'owner')
  const pageId = await sharedPage(owner)
  await open(owner, pageId)
  await prepare(owner, owner, '공개 문서', ['본문'])
  await owner.getByRole('button', { name: '공유' }).click()
  const dialog = owner.getByTestId('share-dialog')
  await dialog.getByLabel('링크 등급').selectOption('VIEW')
  await dialog.getByRole('button', { name: '링크 만들기' }).click()
  const url = await dialog.getByTestId('share-link-url').inputValue()

  const visitor = await (await browser.newContext()).newPage()
  await visitor.goto(new URL(url).pathname)
  await expect(visitor.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  await expect.poll(async () => blocks(visitor)).toEqual(['공개 문서', '본문'])
  await expect(visitor.getByTestId('readonly')).toBeVisible()
  await expect(visitor.getByTestId('sidebar')).toHaveCount(0)

  await dialog.getByLabel('링크 등급').selectOption('EDIT')
  await dialog.getByRole('button', { name: '저장' }).click()
  await expect(visitor.locator('.ProseMirror')).toHaveAttribute('contenteditable', 'true', { timeout: 1_000 })
  await caretAtEnd(visitor, 1)
  await visitor.keyboard.type('!')
  expect(await converged(owner, visitor)).toEqual(['공개 문서', '본문!'])

  await dialog.getByRole('button', { name: '링크 끄기' }).click()
  await expect(visitor.getByTestId('status')).toHaveAttribute('data-status', 'error', { timeout: 1_000 })
})

test('보내지 못한 편집은 탭을 닫았다 열어도 남아 있다가 연결되면 한 번만 확정된다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['본문'])

  // 서버로 가는 편집만 막는다. 편집은 화면에만 있고 서버는 모른다.
  await a.evaluate(() => {
    const send = WebSocket.prototype.send
    WebSocket.prototype.send = function (data) {
      if (typeof data === 'string' && data.includes('"t":"change"')) {
        return
      }
      send.call(this, data)
    }
  })
  await caretAtEnd(a, 1)
  await a.keyboard.type(' 오프라인')
  // 브라우저에 남을 때까지 기다린다.
  await expect
    .poll(() =>
      a.evaluate(
        () =>
          new Promise<number>((resolve) => {
            const request = indexedDB.open('copage', 1)
            request.onsuccess = () => {
              const all = request.result.transaction('docs').objectStore('docs').getAll()
              all.onsuccess = () =>
                resolve(
                  (all.result as { session: { inflight: unknown[] | null; buffer: unknown[] } }[])
                    .map((r) => (r.session.inflight?.length ?? 0) + r.session.buffer.length)
                    .reduce((x, y) => x + y, 0),
                )
            }
          }),
      ),
    )
    .toBeGreaterThan(0)
  expect(await blocks(b)).toEqual(['제목', '본문'])
  const context = a.context()
  await a.close()

  const again = await context.newPage()
  await again.goto(`/p/${docId}`)
  await expect(again.getByTestId('status')).toHaveAttribute('data-status', 'saved')
  expect(await converged(again, b)).toEqual(['제목', '본문 오프라인'])
})

test('권한을 잃으면 브라우저에 남긴 그 문서의 기록을 지운다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['본문'])
  const records = () =>
    b.evaluate(
      () =>
        new Promise<number>((resolve) => {
          const request = indexedDB.open('copage', 1)
          request.onsuccess = () => {
            const count = request.result.transaction('docs').objectStore('docs').count()
            count.onsuccess = () => resolve(count.result)
          }
        }),
    )
  await expect.poll(records).toBe(1)

  await apiAs(a, 'DELETE', `/api/pages/${docId}`)

  await expect(b.getByTestId('status')).toHaveAttribute('data-status', 'error', { timeout: 1_000 })
  await expect.poll(records).toBe(0)
})

test('되돌리기는 내 편집만 되돌리고 남이 그 뒤에 한 편집은 남긴다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['본문'])

  await caretAtEnd(a, 1)
  await a.keyboard.type(' 내 글')
  expect(await converged(a, b)).toEqual(['제목', '본문 내 글'])
  await caret(b, 1, 0)
  await b.keyboard.type('남의 ')
  expect(await converged(a, b)).toEqual(['제목', '남의 본문 내 글'])

  await a.keyboard.press('ControlOrMeta+z')
  expect(await converged(a, b)).toEqual(['제목', '남의 본문'])
  await a.keyboard.press('ControlOrMeta+Shift+z')
  expect(await converged(a, b)).toEqual(['제목', '남의 본문 내 글'])
})

test('빈 블록에 슬래시를 치면 메뉴가 뜨고 고른 종류로 바뀐다', async ({ browser }) => {
  const a = await newUser(browser, 'a')
  const docId = await sharedPage(a)
  await open(a, docId)
  await caretAtEnd(a, 0)
  await a.keyboard.type('제목')
  await a.keyboard.press('Enter')
  await a.keyboard.type('/제목2')
  await expect(a.getByTestId('slash-menu')).toContainText('제목 2')
  await a.keyboard.press('Enter')
  await expect(a.getByTestId('slash-menu')).toHaveCount(0)
  await a.keyboard.type('소제목')
  await expect.poll(async () => (await shapes(a))[1]).toEqual(['heading', '0', '소제목', '2'])
  await expect(a.getByTestId('status')).toHaveAttribute('data-status', 'saved')

  // Esc 로 닫으면 친 글자가 남는다.
  await a.keyboard.press('Enter')
  await a.keyboard.type('/x')
  await a.keyboard.press('Escape')
  await expect(a.getByTestId('slash-menu')).toHaveCount(0)
  expect((await blocks(a)).at(-1)).toBe('/x')
})

test('블록을 끌어 다른 블록 뒤로 옮기면 상대 화면에도 옮겨진다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['첫째', '둘째', '셋째'])

  const first = a.locator('.ProseMirror > *').nth(1)
  const third = a.locator('.ProseMirror > *').nth(3)
  await first.hover()
  const handle = a.getByTestId('drag-handle')
  await expect(handle).toBeVisible()
  // dragTo 는 이 손잡이의 HTML5 끌기를 시작하지 못한다. 마우스를 직접 움직인다.
  const from = await handle.boundingBox()
  const to = await third.boundingBox()
  await a.mouse.move((from?.x ?? 0) + 3, (from?.y ?? 0) + 5)
  await a.mouse.down()
  await a.mouse.move((to?.x ?? 0) + 10, (to?.y ?? 0) + (to?.height ?? 20) - 3, { steps: 10 })
  await a.mouse.up()

  expect(await converged(a, b)).toEqual(['제목', '둘째', '셋째', '첫째'])
})

test('이름 붙인 시점으로 복원하면 상대 화면도 바뀌고, 되돌리기로 복원을 되돌린다', async ({ browser }) => {
  const [a, b] = await Promise.all([newUser(browser, 'a'), newUser(browser, 'b')])
  const docId = await sharedPage(a, b)
  await Promise.all([open(a, docId), open(b, docId)])
  await prepare(a, b, '제목', ['첫 판'])

  await a.getByTestId('versions-button').click()
  const panel = a.getByTestId('versions')
  await panel.getByPlaceholder('지금 시점에 이름 붙이기').fill('초안')
  await panel.getByRole('button', { name: '저장' }).click()
  await expect(panel.getByTestId('version-item').first()).toContainText('초안')
  // 기록 창은 편집기 위에 열려 자리를 밀어낸다. 닫고 편집한 뒤 다시 연다.
  await panel.getByRole('button', { name: '닫기' }).click()
  // 편집기 밖(기록 창)에서 돌아오면 클릭의 커서가 잠깐 늦게 잡힌다. 먼저 편집기에 초점을 둔다.
  await a.locator('.ProseMirror').focus()

  await caretAtEnd(a, 1)
  await a.keyboard.type(' 고침')
  await a.keyboard.press('Enter')
  await a.keyboard.type('새 문단')
  expect(await converged(a, b)).toEqual(['제목', '첫 판 고침', '새 문단'])

  await a.getByTestId('versions-button').click()
  await panel.getByTestId('version-item').filter({ hasText: '초안' }).click()
  await expect(a.getByTestId('version-preview')).toContainText('첫 판')
  await panel.getByRole('button', { name: '이 시점으로 복원' }).click()
  await panel.getByTestId('confirm-restore').click()
  expect(await converged(a, b)).toEqual(['제목', '첫 판'])
  await expect(panel.getByTestId('version-item').first()).toContainText('복원')

  await a.locator('.ProseMirror').click()
  await a.keyboard.press('ControlOrMeta+z')
  expect(await converged(a, b)).toEqual(['제목', '첫 판 고침', '새 문단'])
})
