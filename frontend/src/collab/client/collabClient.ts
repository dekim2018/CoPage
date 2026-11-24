import type { Document } from '../ot/document'
import * as J from '../ot/json'
import type { Op } from '../ot/op'
import { DocConnection } from '../transport/connection'
import type { ConnectionState } from '../transport/connection'
import { ClientSession } from './clientSession'
import type { CommittedChange, Submission } from './clientSession'
import { moveCursor } from './presence'
import type { Cursor, Peer } from './presence'
import { parseRole } from './role'
import { lostTexts, salvage } from './salvage'
import type { PageRole } from './role'

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
  /** 내 권한 등급. welcome 때, 그리고 연결 중에 바뀔 때(role 메시지) 알린다. 잃으면 서버가 4003 으로 닫는다. */
  onRole(role: PageRole): void
  /** 거부되거나 버려진 내 편집 가운데 살리지 못한 것 (DC-3). 화면이 보관함에 보여 준다. */
  onLost(lost: LostEdits): void
}

/** 살리지 못한 편집. */
export interface LostEdits {
  /** rejected(충돌), permission, input, empty: 서버가 거부한 분류. stale: 오래 떨어져 다시 맞출 수 없었다 */
  readonly reason: string
  /** 잃은 편집에서 사용자가 친 글. 다시 붙여 넣을 수 있게 보인다 */
  readonly texts: readonly string[]
  readonly count: number
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
  /** 살려서 다시 넣은 편집이 다음에 보낼 묶음에 들어 있다. 그 묶음이 다시 거부되면 또 살리지 않는다(되풀이 막기). */
  private salvagePending = false
  /** 확인되지 않은 내 편집이 있어 커서를 미뤄 두었다. */
  private presenceDeferred = false
  private readonly salvageSeqs = new Set<number>()
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

  /**
   * 커서 위치는 내 화면 문서 기준이다. 화면 문서에 아직 확정되지 않은 내 편집이 있으면 그 위치를 서버가 확정한 문서에서 해석할 수
   * 없다. 그런 커서를 보내면, 남은 그 커서를 받은 뒤 내 편집을 받으면서 커서를 한 번 더 옮겨 한 칸 어긋난다. 그래서 내 편집이
   * 모두 확인될 때까지 미뤘다가 마지막 커서 하나만 보낸다. 확인(ack)은 서버가 내 편집을 남에게 보낸 뒤에 오므로, 그 뒤에 보낸
   * 커서는 남에게 내 편집보다 늦게 닿는다 (docs/05-PROTOCOL.md 7.6).
   */
  private flushPresence(): void {
    if (!this.welcomed) {
      return
    }
    if (this.session?.hasPending()) {
      this.presenceDeferred = true
      return
    }
    this.presenceDeferred = false
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
    this.flushDeferredPresence()
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
    this.flushDeferredPresence()
    this.reportStatus()
  }

  /** 미뤄 둔 커서가 있고 이제 확인되지 않은 내 편집이 없으면 보낸다. 50ms 간격 제한은 그대로 거친다. */
  private flushDeferredPresence(): void {
    if (this.presenceDeferred && this.session !== null && !this.session.hasPending()) {
      this.presenceDeferred = false
      this.sendPresence(this.myCursor)
    }
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
        this.settle()
        this.notify()
        break
      case 'nack': {
        const seq = message.clientSeq as number
        this.session?.onNack(seq)
        // 예전 서버는 code 를 보내지 않는다. 그때는 다시 보내지 않는다.
        this.settle({ code: typeof message.code === 'string' ? message.code : 'unknown', salvaged: this.salvageSeqs.has(seq) })
        this.salvageSeqs.delete(seq)
        this.notify()
        break
      }
      case 'remote': {
        const change = committed(message as unknown as CommittedJson)
        this.session?.onChange(change)
        this.movePeers(change.ops)
        this.settle()
        this.notify()
        break
      }
      case 'presence':
        this.presence(message)
        break
      case 'role':
        this.listener.onRole(parseRole(message.role))
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
    this.listener.onRole(parseRole(message.role))
    queueMicrotask(() => this.sendPresence(this.myCursor))
    const rev = message.rev as number
    if (message.snapshot !== undefined) {
      // 처음 열었거나, 너무 오래 떨어져 대기 편집을 합칠 수 없다(rebase: false). 확정 문서부터 다시 시작한다. 합칠 수 없는
      // 대기 편집은 버리지 않고 보관함에 보인다(DC-3). 복사본 페이지로 남기는 일은 나중이다.
      const stale = this.session?.pendingOps() ?? []
      if (stale.length > 0) {
        this.listener.onLost({ reason: 'stale', texts: lostTexts(stale), count: stale.length })
      }
      this.salvageSeqs.clear()
      this.salvagePending = false
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
    this.settle()
    this.notify()
  }

  /**
   * 상태 기계가 버린 내 편집을 살린다 (DC-3, D-27). 다른 블록의 글과 속성 편집처럼 지금 문서에 그대로 다시 적용할 수 있는 것은
   * 새 편집으로 다시 넣어 보내고, 나머지는 보관함에 알린다. 서버가 권한, 입력, 빈 묶음으로 거부했으면 다시 보내도 거부되므로 모두
   * 보관함으로 간다. 이미 한 번 살려 보낸 묶음이 다시 거부되면 되풀이하지 않는다.
   */
  private settle(nack?: { code: string; salvaged: boolean }): void {
    const session = this.session
    if (session === null) {
      return
    }
    const discards = session.takeDiscarded()
    if (discards.length === 0) {
      return
    }
    const resend = nack === undefined || (nack.code === 'rejected' && !nack.salvaged)
    const { kept, lost } = resend
      ? salvage(session.view(), discards)
      : { kept: [], lost: discards.flatMap((d) => [...d.ops]) }
    if (kept.length > 0) {
      session.edit(kept)
      this.salvagePending = true
    }
    if (lost.length > 0) {
      this.listener.onLost({ reason: nack?.code ?? 'rejected', texts: lostTexts(lost), count: lost.length })
    }
  }

  private flush(): void {
    if (this.held || !this.welcomed || this.session === null) {
      return
    }
    const submission = this.session.poll()
    if (submission !== null) {
      // 살린 편집은 buffer 에 들어가 이 새 묶음에 실린다. resync 가 다시 보내는 예전 inflight 에는 실리지 않는다.
      if (this.salvagePending) {
        this.salvageSeqs.add(submission.clientSeq)
        this.salvagePending = false
      }
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
