/*
 * REST 호출. Access 토큰은 메모리에만 두고(새로고침하면 사라진다), Refresh 토큰은 서버가 HttpOnly 쿠키로 둔다 (docs/07-SECURITY.md
 * 9.1). 401 이 나면 한 번 재발급해 다시 시도한다. 재발급이 409(다른 탭이 방금 재발급함)면 잠시 뒤 한 번 더 시도한다.
 */

export interface UserInfo {
  id: string
  email: string
  name: string
}

export interface TokenResponse {
  accessToken: string
  expiresIn: number
  user: UserInfo
}

/** 서버가 준 RFC 9457 오류. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(detail)
  }
}

let accessToken: string | null = null
let refreshing: Promise<TokenResponse | null> | null = null

export function setAccessToken(token: string | null): void {
  accessToken = token
}

async function errorOf(response: Response): Promise<ApiError> {
  let detail = response.statusText
  try {
    const body = (await response.json()) as { detail?: string }
    detail = body.detail ?? detail
  } catch {
    // 본문이 JSON 이 아니다.
  }
  return new ApiError(response.status, detail)
}

/** Refresh 쿠키로 새 토큰을 받는다. 로그인 상태가 아니면 null. 동시에 여러 번 불러도 요청은 하나만 나간다. */
export function refresh(): Promise<TokenResponse | null> {
  refreshing ??= (async () => {
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        const response = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
        if (response.ok) {
          const tokens = (await response.json()) as TokenResponse
          accessToken = tokens.accessToken
          return tokens
        }
        if (response.status !== 409) {
          accessToken = null
          return null
        }
        await new Promise((resolve) => setTimeout(resolve, 200))
      }
      return null
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

/** 인증이 필요한 API 를 부른다. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const send = () =>
    fetch(path, {
      ...init,
      credentials: 'same-origin',
      headers: {
        ...(init.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(accessToken === null ? {} : { Authorization: `Bearer ${accessToken}` }),
        ...init.headers,
      },
    })
  let response = await send()
  if (response.status === 401 && (await refresh()) !== null) {
    response = await send()
  }
  if (!response.ok) {
    throw await errorOf(response)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

/** 로그인 없이 부르는 인증 API. */
export async function authApi<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    throw await errorOf(response)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}
