import { afterEach, describe, expect, it, vi } from 'vitest'
import { DocConnection, FatalConnectionError } from './connection'
import type { ConnectionState } from './connection'

afterEach(() => {
  vi.useRealTimers()
})

function connection(ticket: () => Promise<string>, states: ConnectionState[]): DocConnection {
  return new DocConnection(() => 'ws://unused', ticket, {
    onOpen: () => {},
    onMessage: () => {},
    onState: (state) => states.push(state),
  })
}

describe('연결', () => {
  it('티켓을 받을 수 없는 오류(로그인 끝남, 권한 없음)면 다시 붙지 않는다', async () => {
    vi.useFakeTimers()
    const states: ConnectionState[] = []
    const ticket = vi.fn().mockRejectedValue(new FatalConnectionError('403 접근 권한이 없다'))

    connection(ticket, states).start()
    await vi.advanceTimersByTimeAsync(120_000)

    expect(ticket).toHaveBeenCalledTimes(1)
    expect(states.at(-1)).toBe('fatal')
  })

  it('그 밖의 실패는 기다렸다 다시 시도한다', async () => {
    vi.useFakeTimers()
    const states: ConnectionState[] = []
    const ticket = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    const c = connection(ticket, states)
    c.start()
    await vi.advanceTimersByTimeAsync(3_000)
    c.stop()

    expect(ticket.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(states).toContain('closed')
  })
})
