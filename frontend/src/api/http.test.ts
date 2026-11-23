import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../stores/auth'
import { refresh, setAccessToken } from './http'

function respond(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
})

describe('토큰 재발급', () => {
  it('서버에 닿지 못하면 예외 대신 로그인 안 됨(null)으로 본다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(refresh()).resolves.toBeNull()
  })

  it('다른 탭이 방금 재발급해 409 가 오면 한 번 더 시도한다', async () => {
    const user = { id: 'u', email: 'a@b.c', name: 'a' }
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(409, { detail: '다른 요청이 방금 재발급했다' }))
      .mockResolvedValueOnce(respond(200, { accessToken: 't', expiresIn: 900, user }))
    vi.stubGlobal('fetch', fetch)

    await expect(refresh()).resolves.toMatchObject({ accessToken: 't' })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})

describe('로그아웃', () => {
  it('서버가 401 을 주거나 서버에 닿지 못해도 이 화면의 로그인 상태는 지운다', async () => {
    setActivePinia(createPinia())
    const auth = useAuth()
    auth.user = { id: 'u', email: 'a@b.c', name: 'a' }
    setAccessToken('expired')
    // 로그아웃 401, 재발급 401
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(401, { detail: '토큰이 없다' })))
    await auth.logout()
    expect(auth.user).toBeNull()

    auth.user = { id: 'u', email: 'a@b.c', name: 'a' }
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await auth.logout()
    expect(auth.user).toBeNull()
  })
})
