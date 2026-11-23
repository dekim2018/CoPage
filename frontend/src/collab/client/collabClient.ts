import type { Document } from '../ot/document'
import * as J from '../ot/json'
import type { Op } from '../ot/op'
import { DocConnection } from '../transport/connection'
import type { ConnectionState } from '../transport/connection'
import { ClientSession } from './clientSession'
import type { CommittedChange, Submission } from './clientSession'
import { moveCursor } from './presence'
import type { Cursor, Peer } from './presence'

/*
 * 문서 하나를 서버와 맞추는 협업 클라이언트 (docs/10-FRONTEND.md 12.1, 12.3). ClientSession 을 감싸 서버 메시지를 처리하고,
 * 화면 문서가 바뀌면 알린다.
 *
 * 한글 조합 중에는 hold() 로 멈춘다. 멈춘 동안 받은 메시지는 쌓아 두고 내 편집도 보내지 않는다. release() 하면 쌓인 메시지를
 * 처리하고 조합으로 생긴 편집을 한 묶음으로 보낸다 (12.2). 그동안 편집기 문서와 화면 문서가 어긋나지 않는다.
 */

export type SyncStatus = 'connecting' | 'saved' | 'saving' | 'offline' | 'error'

export interface CollabListener {
  /** 화면 문서가 서버 쪽 사정으로 바뀌었다. 편집기에 반영한다. */
  onRemote(view: Document): void
  onStatus(status: SyncStatus, detail?: string): void
  /** 접속자나 남의 커서가 바뀌었다. */
  onPeers(peers: readonly Peer[]): void
}

/** 커서가 움직여도 이 간격에 한 번만 보낸다 (docs/05-PROTOCOL.md 7.6). */
const PRESENCE_INTERVAL_MS = 50

interface CommittedJson {
  rev: number
  clientId: string
  clientSeq: number
  ops: unknown
}

export class CollabClient {
  /** 탭마다 다르다 (12.4). 같은 사람이 탭 두 개를 열면 서로 다른 참여자다. */
  readonly clientId = crypto.randomUUID()
  private session: ClientSession | null = null
  private welcomed = false
  private held = false
  private readonly queue: unknown[] = []
  private readonly connection: DocConnection
  private connectionState: ConnectionState = 'connecting'
  private readonly peers = new Map<string, Peer>()
  private myCursor: Cursor | null = null
  private lastPresenceAt = 0
  private presenceTimer: ReturnType<typeof setTimeout> | null = null

  constructor(
    docId: string,
    ticket: () => Promise<string>,
    private readonly listener: CollabListener,
    wsBase: string = defaultWsBase(),
  ) {
    this.connection = new DocConnection(
      (t) => `${wsBase}/ws/docs/${docId}?ticket=${encodeURIComponent(t)}&v=1`,
      ticket,
      {
        onOpen: () => this.hello(),
        onMessage: (message) => this.receive(message),
        onState: (state, detail) => {
          this.connectionState = state
          if (state !== 'open') {
            this.welcomed = false
          }
          this.reportStatus(detail)
        },
      },
    )
  }

  start(): void {
    this.connection.start()
  }

  stop(): void {
    if (this.presenceTimer !== null) {
      clearTimeout(this.presenceTimer)
    }
    this.connection.stop()
  }

  /**
   * 내 커서를 알린다. 커서가 움직여도 50ms 에 한 번만 보내고, 사이에 온 것은 마지막 것만 모아 보낸다. null 이면 편집기를 떠난
   * 것이다.
   */
  sendPresence(cursor: Cursor | null): void {
    this.myCursor = cursor
    if (this.presenceTimer !== null) {
      return
    }
    const wait = this.lastPresenceAt + PRESENCE_INTERVAL_MS - Date.now()
    if (wait <= 0) {
      this.flushPresence()
    } else {
      this.presenceTimer = setTimeout(() => {
        this.presenceTimer = null
        this.flushPresence()
      }, wait)
    }
  }

  private flushPresence(): void {
    if (!this.welcomed) {
      return
    }
    this.lastPresenceAt = Date.now()
    this.connection.send({ t: 'presence', cursor: this.myCursor })
  }

  /** 지금 화면 문서. 첫 welcome 전에는 null. */
  view(): Document | null {
    return this.session?.view() ?? null
  }

  /** 내 편집을 화면 문서에 바로 반영하고 보낼 차례가 되면 보낸다. 지금 화면 문서에 적용할 수 없으면 예외. */
  edit(ops: readonly Op[]): void {
    if (this.session === null || ops.length === 0) {
      return
    }
    this.session.edit(ops)
    // 내 편집도 남의 커서를 옮긴다.
    this.movePeers(ops)
    this.flush()
    this.reportStatus()
  }

  private movePeers(ops: readonly Op[]): void {
    let moved = false
    for (const [clientId, peer] of this.peers) {
      if (peer.cursor !== null) {
        this.peers.set(clientId, { ...peer, cursor: moveCursor(peer.cursor, ops) })
        moved = true
      }
    }
    if (moved) {
      this.listener.onPeers([...this.peers.values()])
    }
  }

  hold(): void {
    this.held = true
  }

  release(): void {
    this.held = false
    const pending = this.queue.splice(0)
    for (const message of pending) {
      this.handle(message)
    }
    this.flush()
    this.reportStatus()
  }

  private hello(): void {
    const session = this.session
    this.connection.send({
      t: 'hello',
      clientId: this.clientId,
      lastRev: session?.confirmedRev() ?? 0,
      resume: session !== null,
    })
  }

  private receive(message: unknown): void {
    if (this.held) {
      this.queue.push(message)
      return
    }
    this.handle(message)
    this.flush()
    this.reportStatus()
  }

  private handle(raw: unknown): void {
    const message = raw as Record<string, unknown>
    switch (message.t) {
      case 'welcome':
        this.welcome(message)
        break
      case 'ack':
        // 화면에서 빼 두었던 inflight 가 확정되면 화면 문서도 바뀐다.
        this.session?.onAck(message.clientSeq as number, message.rev as number)
        this.notify()
        break
      case 'nack':
        this.session?.onNack(message.clientSeq as number)
        this.notify()
        break
      case 'remote': {
        const change = committed(message as unknown as CommittedJson)
        this.session?.onChange(change)
        this.movePeers(change.ops)
        this.notify()
        break
      }
      case 'presence':
        this.presence(message)
        break
    }
  }

  private presence(message: Record<string, unknown>): void {
    const clientId = message.clientId as string
    if (message.left === true) {
      this.peers.delete(clientId)
    } else {
      this.peers.set(clientId, message as unknown as Peer)
    }
    this.listener.onPeers([...this.peers.values()])
  }

  private welcome(message: Record<string, unknown>): void {
    // 접속자 목록은 다시 붙을 때마다 서버가 준 것으로 새로 시작한다. 내 마지막 커서도 다시 알린다.
    this.peers.clear()
    for (const peer of (message.peers as Peer[] | undefined) ?? []) {
      this.peers.set(peer.clientId, peer)
    }
    this.listener.onPeers([...this.peers.values()])
    queueMicrotask(() => this.sendPresence(this.myCursor))
    const rev = message.rev as number
    if (message.snapshot !== undefined) {
      // 처음 열었거나, 너무 오래 떨어져 대기 편집을 합칠 수 없다(rebase: false). 확정 문서부터 다시 시작한다.
      this.session = new ClientSession(this.clientId, J.decodeDocument(message.snapshot), rev)
      this.welcomed = true
      this.notify()
      return
    }
    const missed = (message.ops as CommittedJson[]).map(committed)
    this.welcomed = true
    const resend = (this.session as ClientSession).resync(missed)
    if (resend !== null) {
      this.sendChange(resend)
    }
    this.notify()
  }

  private flush(): void {
    if (this.held || !this.welcomed || this.session === null) {
      return
    }
    const submission = this.session.poll()
    if (submission !== null) {
      this.sendChange(submission)
    }
  }

  private sendChange(submission: Submission): void {
    this.connection.send({
      t: 'change',
      clientSeq: submission.clientSeq,
      baseRev: submission.baseRev,
      ops: J.encodeOps(submission.ops),
    })
  }

  private notify(): void {
    if (this.session !== null) {
      this.listener.onRemote(this.session.view())
    }
  }

  private reportStatus(detail?: string): void {
    if (this.connectionState === 'fatal') {
      this.listener.onStatus('error', detail)
    } else if (this.connectionState !== 'open') {
      this.listener.onStatus(this.session === null ? 'connecting' : 'offline', detail)
    } else if (this.session?.hasPending()) {
      this.listener.onStatus('saving')
    } else if (this.welcomed) {
      this.listener.onStatus('saved')
    }
  }
}

function committed(json: CommittedJson): CommittedChange {
  return { rev: json.rev, clientId: json.clientId, clientSeq: json.clientSeq, ops: J.decodeOps(json.ops) }
}

function defaultWsBase(): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`
}
