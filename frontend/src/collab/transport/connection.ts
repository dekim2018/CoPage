/*
 * 문서 하나의 WebSocket 연결 (docs/05-PROTOCOL.md 7.1, 7.4, docs/10-FRONTEND.md 12.1).
 *
 * 연결할 때마다 1회용 티켓을 새로 받는다. 끊기면 1초부터 두 배씩 최대 30초까지 기다렸다 다시 붙고, 노드 하나에 재접속이 몰리지
 * 않도록 기다리는 시간을 무작위로 0.5~1배 줄인다. 20초마다 ping 을 보낸다.
 */

export type ConnectionState = 'connecting' | 'open' | 'closed' | 'fatal'

/** 티켓을 받는 쪽이 이 오류를 던지면 다시 붙지 않는다. 로그인이 끝났거나 문서에 접근할 수 없는 경우다. */
export class FatalConnectionError extends Error {
  override name = 'FatalConnectionError'
}

export interface ConnectionHandlers {
  /** 연결이 열렸다. hello 를 보낼 차례다. */
  onOpen(): void
  onMessage(message: unknown): void
  onState(state: ConnectionState, detail?: string): void
}

/** 다시 붙어도 소용없는 종료 코드. 권한 없음(4003), 규약 위반(4008), 규약 버전 불일치(4010). */
const FATAL_CODES = new Set([4003, 4008, 4010])
const PING_INTERVAL_MS = 20_000
const MIN_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 30_000

export class DocConnection {
  private socket: WebSocket | null = null
  private attempt = 0
  private stopped = false
  private ping: ReturnType<typeof setInterval> | null = null
  private retry: ReturnType<typeof setTimeout> | null = null

  constructor(
    private readonly url: (ticket: string) => string,
    private readonly ticket: () => Promise<string>,
    private readonly handlers: ConnectionHandlers,
  ) {}

  start(): void {
    this.stopped = false
    void this.connect()
  }

  stop(): void {
    this.stopped = true
    if (this.retry !== null) {
      clearTimeout(this.retry)
    }
    this.clearPing()
    this.socket?.close(1000)
    this.socket = null
  }

  isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  send(message: unknown): boolean {
    if (!this.isOpen()) {
      return false
    }
    ;(this.socket as WebSocket).send(JSON.stringify(message))
    return true
  }

  private async connect(): Promise<void> {
    this.handlers.onState('connecting')
    let ticket: string
    try {
      ticket = await this.ticket()
    } catch (e) {
      if (e instanceof FatalConnectionError) {
        this.handlers.onState('fatal', e.message)
        return
      }
      this.scheduleRetry(String(e))
      return
    }
    if (this.stopped) {
      return
    }
    const socket = new WebSocket(this.url(ticket))
    this.socket = socket
    socket.onopen = () => {
      this.attempt = 0
      this.ping = setInterval(() => this.send({ t: 'ping' }), PING_INTERVAL_MS)
      this.handlers.onState('open')
      this.handlers.onOpen()
    }
    socket.onmessage = (event) => {
      this.handlers.onMessage(JSON.parse(String(event.data)))
    }
    socket.onclose = (event) => {
      if (this.socket !== socket) {
        return
      }
      this.socket = null
      this.clearPing()
      if (this.stopped) {
        return
      }
      if (FATAL_CODES.has(event.code)) {
        this.handlers.onState('fatal', `${event.code} ${event.reason}`)
        return
      }
      this.scheduleRetry(`${event.code} ${event.reason}`)
    }
  }

  private scheduleRetry(detail: string): void {
    if (this.stopped) {
      return
    }
    this.handlers.onState('closed', detail)
    const backoff = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * 2 ** this.attempt)
    this.attempt++
    this.retry = setTimeout(() => void this.connect(), backoff * (0.5 + Math.random() / 2))
  }

  private clearPing(): void {
    if (this.ping !== null) {
      clearInterval(this.ping)
      this.ping = null
    }
  }
}
