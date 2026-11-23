import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../stores/auth'
import { ApiError, api, refresh, setAccessToken } from './http'

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

describe('서버가 잠시 쓸 수 없을 때 (503)', () => {
  it('재발급이 503 이면 로그인이 끝난 것으로 보지 않고 원래 요청을 503 으로 실패시킨다', async () => {
    setAccessToken('expired')
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(401, { detail: '토큰이 만료되었다' }))
      .mockResolvedValueOnce(respond(503, { detail: '잠시 서비스를 쓸 수 없다' }))
    vi.stubGlobal('fetch', fetch)

    const failure = await api('/api/docs/d/ws-ticket', { method: 'POST' }).catch((e: unknown) => e)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(503)
    // Access 토큰을 지우지 않았다. 다음 요청에도 붙는다.
    const next = vi.fn().mockResolvedValue(respond(200, { ok: true }))
    vi.stubGlobal('fetch', next)
    await api('/api/me')
    expect(next).toHaveBeenCalledWith(
      '/api/me',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer expired' }) }),
    )
  })

  it('재발급 중 서버에 닿지 못해도 원래 요청을 401 이 아니라 그 오류로 실패시킨다', async () => {
    setAccessToken('expired')
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(respond(401, { detail: '토큰이 만료되었다' }))
        .mockRejectedValueOnce(new TypeError('Failed to fetch')),
    )

    await expect(api('/api/me')).rejects.toBeInstanceOf(TypeError)
  })

  it('재발급이 401 이면 지금처럼 로그인이 끝난 것으로 본다', async () => {
    setAccessToken('expired')
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(respond(401, { detail: '토큰이 만료되었다' }))
        .mockResolvedValueOnce(respond(401, { detail: 'Refresh 토큰이 올바르지 않다' })),
    )

    const failure = await api('/api/me').catch((e: unknown) => e)
    expect((failure as ApiError).status).toBe(401)
  })

  it('첫 화면의 재발급이 503 이어도 예외 없이 null 이다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(503, { detail: '잠시 서비스를 쓸 수 없다' })))

    await expect(refresh()).resolves.toBeNull()
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
