/*
 * REST 호출. Access 토큰은 메모리에만 두고(새로고침하면 사라진다), Refresh 토큰은 서버가 HttpOnly 쿠키로 둔다 (docs/07-SECURITY.md
 * 9.1). 401 이 나면 한 번 재발급해 다시 시도한다. 재발급이 409(다른 탭이 방금 재발급함)면 잠시 뒤 한 번 더 시도한다.
 *
 * 재발급이 5xx(서버가 Redis 에 닿지 못하는 동안 503, 9.7)이거나 서버에 닿지 못하면 로그인이 끝난 것이 아니다. Access 토큰을 지우지
 * 않고, 원래 요청은 401 대신 그 오류로 실패시켜 부른 쪽이 로그인 화면으로 보내지 않고 다시 시도하게 한다.
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
/** 재발급 결과. tokens 가 null 이면 unavailable 이 있을 때만 일시적인 실패다. */
interface RefreshOutcome {
  tokens: TokenResponse | null
  unavailable: unknown
}

let refreshing: Promise<RefreshOutcome> | null = null

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

/**
 * Refresh 쿠키로 새 토큰을 받는다. 로그인 상태가 아니면 null. 동시에 여러 번 불러도 요청은 하나만 나간다. 서버에 닿지 못한
 * 경우(꺼져 있거나 오프라인)도 null 이다. 예외를 던지면 첫 화면의 라우터 가드가 실패해 빈 화면이 된다.
 */
export async function refresh(): Promise<TokenResponse | null> {
  return (await tryRefresh()).tokens
}

function tryRefresh(): Promise<RefreshOutcome> {
  refreshing ??= (async (): Promise<RefreshOutcome> => {
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        let response: Response
        try {
          response = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
        } catch (e) {
          return { tokens: null, unavailable: e }
        }
        if (response.ok) {
          const tokens = (await response.json()) as TokenResponse
          accessToken = tokens.accessToken
          return { tokens, unavailable: null }
        }
        if (response.status >= 500) {
          return { tokens: null, unavailable: await errorOf(response) }
        }
        if (response.status !== 409) {
          accessToken = null
          return { tokens: null, unavailable: null }
        }
        await new Promise((resolve) => setTimeout(resolve, 200))
      }
      return { tokens: null, unavailable: null }
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
  if (response.status === 401) {
    const outcome = await tryRefresh()
    if (outcome.tokens !== null) {
      response = await send()
    } else if (outcome.unavailable !== null) {
      throw outcome.unavailable
    }
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
