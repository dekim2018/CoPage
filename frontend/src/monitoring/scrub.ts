/**
 * 에러 모니터링으로 보내기 전에 비밀을 가린다 (D-49). 서버의 ErrorReportScrubber, 프록시의 접근 로그(Caddyfile)와 같은 규칙이다.
 * 주소의 WebSocket 티켓, 공유 링크와 초대의 토큰, Bearer 토큰이 대상이다.
 */
const SECRETS =
  /(\/(?:s|api\/share|invite|api\/invites)\/)[^/?#\s"']+|((?:[?&]|\b)ticket=)[^&#\s"']*|(Bearer\s+)[A-Za-z0-9._~+/=-]+/g

export function scrub(text: string): string
export function scrub(text: string | undefined): string | undefined
export function scrub(text: string | undefined): string | undefined {
  return text?.replace(SECRETS, (_match, path?: string, ticket?: string, bearer?: string) => `${path ?? ''}${ticket ?? ''}${bearer ?? ''}REDACTED`)
}

/** 객체 안의 글을 모두 가린다. 이벤트와 빵부스러기(breadcrumb)의 모양을 몰라도 빠뜨리지 않게 한다. */
export function scrubDeep<T>(value: T, depth = 0): T {
  if (depth > 8) {
    return value
  }
  if (typeof value === 'string') {
    return scrub(value) as T
  }
  if (Array.isArray(value)) {
    return value.map((item: unknown) => scrubDeep(item, depth + 1)) as T
  }
  if (value !== null && typeof value === 'object') {
    for (const key of Object.keys(value)) {
      const record = value as Record<string, unknown>
      record[key] = scrubDeep(record[key], depth + 1)
    }
  }
  return value
}
