import { describe, expect, it } from 'vitest'
import { scrub, scrubDeep } from './scrub'

describe('scrub', () => {
  it('티켓, 공유와 초대 토큰, Bearer 토큰을 가리고 나머지는 둔다', () => {
    expect(scrub('wss://x/ws/docs/1?ticket=abc&v=1')).toBe('wss://x/ws/docs/1?ticket=REDACTED&v=1')
    expect(scrub('https://x/s/tok123')).toBe('https://x/s/REDACTED')
    expect(scrub('/api/share/tok/ws-ticket')).toBe('/api/share/REDACTED/ws-ticket')
    expect(scrub('/invite/tok?next=/')).toBe('/invite/REDACTED?next=/')
    expect(scrub('Authorization: Bearer eyJ.a.b')).toBe('Authorization: Bearer REDACTED')
    expect(scrub('/api/docs/1/comments')).toBe('/api/docs/1/comments')
    expect(scrub(undefined)).toBeUndefined()
  })

  it('이벤트 안의 글을 깊이 가린다', () => {
    const event = {
      request: { url: 'https://x/s/secret' },
      breadcrumbs: [{ data: { url: '/ws/docs/1?ticket=t' } }],
      count: 3,
    }
    expect(scrubDeep(event)).toEqual({
      request: { url: 'https://x/s/REDACTED' },
      breadcrumbs: [{ data: { url: '/ws/docs/1?ticket=REDACTED' } }],
      count: 3,
    })
  })
})
