import { append } from '../ot/changes'
import type { Document } from '../ot/document'
import { InvalidOperationError, TransformConflictError } from '../ot/errors'
import * as J from '../ot/json'
import type { Op } from '../ot/op'
import { transform } from '../ot/transformer'
import type { Discard } from './salvage'
import { takeWithin } from './sendBudget'

/** 클라이언트가 서버로 보내는 편집 묶음. baseRev 는 이 묶음을 만들 때 클라이언트가 알고 있던 마지막 리비전이다. */
export interface Submission {
  readonly clientId: string
  readonly clientSeq: number
  readonly baseRev: number
  readonly ops: readonly Op[]
}

/**
 * 서버가 확정한 연산 묶음 하나. rev 는 이 묶음이 만든 리비전, (clientId, clientSeq) 는 보낸 쪽과 그쪽이 붙인 순번이다.
 * ops 는 앞선 확정 묶음들에 맞게 변환이 끝난 연산이다.
 */
export interface CommittedChange {
  readonly rev: number
  readonly clientId: string
  readonly clientSeq: number
  readonly ops: readonly Op[]
}

/**
 * 클라이언트 한 개(브라우저 탭 하나)가 문서를 서버와 맞추는 상태 기계. Java 의 `com.copage.ot.sync.ClientSession` 과 같은
 * 규칙이다.
 *
 * 세 가지를 들고 있다.
 *
 * - 확정 문서: 서버가 확정한 묶음만 순서대로 적용한 문서. 항상 서버의 어느 리비전과 정확히 같다.
 * - inflight: 서버로 보내고 답을 기다리는 묶음. 한 번에 하나만 보낸다.
 * - buffer: 아직 보내지 않은 묶음.
 *
 * 화면에 보이는 문서는 확정 문서에 inflight 와 buffer 를 차례로 적용한 결과다. 다른 사람의 묶음이 오면 확정 문서에 적용하고,
 * inflight 와 buffer 를 그 묶음에 맞게 변환한 뒤 화면 문서를 다시 계산한다. inflight 를 변환하는 순서와 함수가 서버와
 * 같으므로, 서버가 확정한 내 묶음과 여기서 들고 있는 inflight 는 항상 같은 값이 된다.
 */
/**
 * 브라우저에 남겨 두는 상태 기계 (docs/10-FRONTEND.md 12.4, R-6). 탭을 닫았다 열어도 보내지 못한 편집을 이어서 보낸다. 연산과
 * 문서는 규약 JSON 이다.
 */
export interface PersistedSession {
  version: 1
  clientId: string
  rev: number
  nextSeq: number
  confirmed: J.DocumentJson
  inflight: J.OpJson[] | null
  inflightSeq: number
  inflightInView: boolean
  inflightDead: boolean
  buffer: J.OpJson[]
}

export class ClientSession {
  private confirmedDoc: Document
  private rev: number
  private nextSeq = 1

  private inflight: readonly Op[] | null = null
  private inflightSeq = 0
  /** inflight 가 화면 문서에 반영되어 있으면 true. false 면 buffer 는 확정 문서 바로 위에 쌓인 것이다. */
  private inflightInView = false
  /** 변환 충돌이 나서 서버가 반드시 거부할 inflight 면 true. */
  private inflightDead = false

  private buffer: readonly Op[] = []
  private viewDoc: Document
  /**
   * 이 상태 기계가 버린 내 편집 (DC-3). 상태 기계의 동작은 Java 와 같고, 버린 것을 따로 적어 둘 뿐이다. 협업 클라이언트가
   * takeDiscarded 로 꺼내 살릴 것을 고른다(salvage.ts).
   */
  private discards: Discard[] = []
  /** 남의 편집이 화면 문서에 준 변화. null 은 옮길 수 없었던 것이다. takeViewChanges 로 꺼낸다. */
  private viewChanges: (readonly Op[] | null)[] = []
  /** 지금 inflight 를 이미 discards 에 적었는지. 죽은 inflight 는 다른 사람의 편집이 올 때 적고, nack 때 다시 적지 않는다. */
  private inflightRecorded = false

  constructor(
    readonly clientId: string,
    document: Document,
    rev: number,
  ) {
    this.confirmedDoc = document
    this.rev = rev
    this.viewDoc = document
  }

  /** 브라우저에 남길 상태. 버린 편집(discards)은 이미 화면에 알렸으므로 남기지 않는다. */
  export(): PersistedSession {
    return {
      version: 1,
      clientId: this.clientId,
      rev: this.rev,
      nextSeq: this.nextSeq,
      confirmed: J.encodeDocument(this.confirmedDoc),
      inflight: this.inflight === null ? null : J.encodeOps(this.inflight),
      inflightSeq: this.inflightSeq,
      inflightInView: this.inflightInView,
      inflightDead: this.inflightDead,
      buffer: J.encodeOps(this.buffer),
    }
  }

  /**
   * 남겨 둔 상태로 다시 만든다. 같은 clientId 와 순번을 이어 쓰므로, 연결이 돌아와 resync 하면 예전 inflight 는 같은 순번으로
   * 다시 보내지고 서버가 이미 확정했으면 처음 리비전으로 ack 한다 (7.5).
   */
  static restore(saved: PersistedSession): ClientSession {
    const session = new ClientSession(saved.clientId, J.decodeDocument(saved.confirmed), saved.rev)
    session.nextSeq = saved.nextSeq
    session.inflight = saved.inflight === null ? null : J.decodeOps(saved.inflight)
    session.inflightSeq = saved.inflightSeq
    session.inflightInView = saved.inflightInView
    session.inflightDead = saved.inflightDead
    session.buffer = J.decodeOps(saved.buffer)
    session.rebuildView()
    return session
  }

  /** 마지막으로 꺼낸 뒤 남의 편집이 화면 문서에 준 변화들. 차례대로다. null 은 옮길 수 없었던 변화다. */
  takeViewChanges(): (readonly Op[] | null)[] {
    return this.viewChanges.splice(0)
  }

  /** 화면에 보이는 문서. 내 편집이 서버 확인 전에 먼저 반영되어 있다. */
  view(): Document {
    return this.viewDoc
  }

  confirmed(): Document {
    return this.confirmedDoc
  }

  confirmedRev(): number {
    return this.rev
  }

  /** 서버 확인을 기다리거나 아직 보내지 않은 편집이 있으면 true. */
  hasPending(): boolean {
    return this.inflight !== null || this.buffer.length > 0
  }

  /** 아직 보내지 않은 편집이 있는지. 큰 묶음을 나눠 보내는 중이면 poll 뒤에도 true 다. */
  hasBuffer(): boolean {
    return this.buffer.length > 0
  }

  /**
   * 내 편집을 화면 문서에 바로 적용하고 buffer 에 쌓는다.
   *
   * @throws InvalidOperationError 지금 화면 문서에 적용할 수 없는 편집일 때. 상태는 바뀌지 않는다
   */
  edit(ops: readonly Op[]): void {
    this.viewDoc = this.viewDoc.apply(ops)
    this.buffer = append(this.buffer, ops)
  }

  /**
   * 보낼 묶음이 있고 답을 기다리는 묶음이 없으면, buffer 를 inflight 로 옮기고 보낼 내용을 돌려준다. 없으면 null.
   *
   * buffer 가 메시지 크기 한도(sendBudget)를 넘으면(큰 붙여넣기) 앞에서부터 한도 안에 드는 만큼만 옮기고 나머지는 buffer 에
   * 남긴다. 나머지는 ack 뒤 다음 poll 이 보낸다 (docs/05-PROTOCOL.md 7.7).
   */
  poll(): Submission | null {
    if (this.inflight !== null || this.buffer.length === 0) {
      return null
    }
    const [head, rest] = takeWithin(this.buffer)
    this.inflight = head
    this.inflightSeq = this.nextSeq++
    this.inflightInView = true
    this.inflightDead = false
    this.inflightRecorded = false
    this.buffer = rest
    return { clientId: this.clientId, clientSeq: this.inflightSeq, baseRev: this.rev, ops: this.inflight }
  }

  /** 서버가 확정한 묶음을 받는다. 내 inflight 가 확정된 것이면 ack 로, 아니면 다른 사람의 편집으로 처리한다. */
  onChange(change: CommittedChange): void {
    if (change.rev !== this.rev + 1) {
      throw new Error(`리비전 ${this.rev + 1} 을 기다리는데 ${change.rev} 이 왔다`)
    }
    if (this.inflight !== null && this.clientId === change.clientId && change.clientSeq === this.inflightSeq) {
      this.onAck(change.clientSeq, change.rev)
    } else {
      this.onRemote(change.ops)
    }
  }

  /** 내 inflight 가 rev 로 확정되었다. 기다리던 순번이 아니면 무시한다. */
  onAck(clientSeq: number, rev: number): void {
    if (this.isStale(clientSeq)) {
      return
    }
    if (this.inflightDead) {
      throw new Error('변환 충돌이 난 묶음에 ack 가 왔다. 서버와 변환 규칙이 다르다')
    }
    const ops = this.inflight as readonly Op[]
    if (ops.length > 0) {
      if (rev !== this.rev + 1) {
        throw new Error(`ack 리비전이 맞지 않는다: 기대 ${this.rev + 1}, 실제 ${rev}`)
      }
      this.confirmedDoc = this.confirmedDoc.apply(ops)
      this.rev = rev
    }
    this.inflight = null
    if (!this.inflightInView) {
      // 화면에서 빼 두었던 묶음이 확정되었다. buffer 입장에서는 다른 사람의 편집이 온 것과 같다.
      this.buffer = this.transformOrDrop(this.buffer, ops)
      this.rebuildView()
    }
  }

  /** 내 inflight 가 거부되었다. 그 위에 쌓은 buffer 도 함께 버리고 화면을 확정 문서로 되돌린다. */
  onNack(clientSeq: number): void {
    if (this.isStale(clientSeq)) {
      return
    }
    if (!this.inflightRecorded) {
      this.record([...(this.inflight ?? []), ...(this.inflightInView ? this.buffer : [])], null)
    }
    this.inflight = null
    if (this.inflightInView) {
      this.buffer = []
    }
    this.rebuildView()
  }

  /** 버린 내 편집을 꺼낸다. 꺼낸 뒤에는 비어 있다. */
  takeDiscarded(): Discard[] {
    const discards = this.discards
    this.discards = []
    return discards
  }

  /** 아직 확정되지 않은 내 편집(inflight 와 buffer). 확정 문서 기준이다. 오래 떨어져 다시 맞출 수 없을 때 보관함에 쓴다. */
  pendingOps(): readonly Op[] {
    return [...(this.inflight !== null && this.inflightInView ? this.inflight : []), ...this.buffer]
  }

  private record(ops: readonly Op[], against: readonly Op[] | null, blocked: ReadonlySet<string> = new Set()): void {
    if (ops.length > 0) {
      this.discards.push({ ops, against, blocked })
    }
  }

  /**
   * 연결이 끊겼다 돌아왔을 때 호출한다. 놓친 묶음을 반영하고, 다시 보내야 할 묶음이 있으면 돌려준다.
   *
   * 끊기기 전에 보낸 inflight 가 서버에 들어갔다면 missed 안에 내 clientId 와 순번으로 들어 있어 ack 로 처리된다. 들어가지
   * 않았다면 같은 순번으로 다시 보낸다. 두 번 도착해도 서버가 순번으로 걸러 낸다.
   */
  resync(missed: readonly CommittedChange[]): Submission | null {
    for (const change of missed) {
      this.onChange(change)
    }
    if (this.inflight === null) {
      return this.poll()
    }
    if (this.inflightDead || this.inflight.length === 0) {
      // 보내도 거부될 묶음이거나, 변환하고 나니 할 일이 없어진 묶음이다.
      if (this.inflightDead && !this.inflightRecorded) {
        this.record(this.inflight, null)
      }
      this.inflight = null
      this.rebuildView()
      return this.poll()
    }
    return { clientId: this.clientId, clientSeq: this.inflightSeq, baseRev: this.rev, ops: this.inflight }
  }

  private onRemote(ops: readonly Op[]): void {
    // 화면 문서에 실제로 일어난 변화(남의 편집을 내 대기 편집 위로 옮긴 것). 되돌리기 목록을 맞추는 데 쓴다 (E-8).
    try {
      this.viewChanges.push(transform(ops, this.pendingOps(), false).a)
    } catch (e) {
      if (!(e instanceof TransformConflictError)) {
        throw e
      }
      this.viewChanges.push(null)
    }
    this.confirmedDoc = this.confirmedDoc.apply(ops)
    this.rev++

    let overInflight = ops
    const wasInView = this.inflight !== null && this.inflightInView
    if (this.inflight !== null && !this.inflightDead) {
      try {
        const pair = transform(this.inflight, ops, true)
        this.inflight = pair.a
        overInflight = pair.b
      } catch (e) {
        if (!(e instanceof TransformConflictError)) {
          throw e
        }
        this.inflightDead = true
      }
    }
    if (wasInView && this.inflightDead) {
      // inflight 가 거부될 것이 확실하다. 그 위에 쌓은 buffer 는 의미를 잃었으므로 버린다. 둘 다 이 편집 전 확정 문서
      // 기준이라, 이 편집(ops)에 맞춰 살릴 수 있게 적는다.
      this.record([...(this.inflight as readonly Op[]), ...this.buffer], ops)
      this.inflightRecorded = true
      this.inflightInView = false
      this.buffer = []
    } else if (wasInView) {
      this.buffer = this.transformOrDrop(this.buffer, overInflight)
    } else {
      this.buffer = this.transformOrDrop(this.buffer, ops)
    }
    this.rebuildView()
  }

  private rebuildView(): void {
    let base = this.confirmedDoc
    if (this.inflight !== null && this.inflightInView) {
      try {
        base = this.confirmedDoc.apply(this.inflight)
      } catch (e) {
        if (!(e instanceof InvalidOperationError)) {
          throw e
        }
        // 지금 확정 문서에는 적용할 수 없다. 서버의 판정이 올 때까지 화면에서 빼 둔다. buffer 는 inflight 를 적용한 상태
        // 기준이라 inflight 가 건드린 블록의 편집은 살릴 수 없다.
        this.record(this.buffer, null, touchedBlocks(this.inflight))
        this.inflightInView = false
        this.buffer = []
      }
    }
    try {
      this.viewDoc = base.apply(this.buffer)
    } catch (e) {
      if (!(e instanceof InvalidOperationError)) {
        throw e
      }
      this.record(this.buffer, null)
      this.buffer = []
      this.viewDoc = base
    }
  }

  /** pending 을 against 에 맞춰 변환한다. 충돌하면 Java 와 같이 통째로 버리고, 버린 것을 적는다. */
  private transformOrDrop(pending: readonly Op[], against: readonly Op[]): readonly Op[] {
    try {
      return transform(pending, against, true).a
    } catch (e) {
      if (!(e instanceof TransformConflictError)) {
        throw e
      }
      this.record(pending, against)
      return []
    }
  }

  /**
   * 지금 기다리는 묶음에 대한 답이 아니면 true. 재접속 뒤에 예전 연결로 보낸 묶음의 답이 늦게 올 수 있고, 그런 답은 이미 다른
   * 경로로 처리했으므로 무시한다.
   */
  private isStale(clientSeq: number): boolean {
    return this.inflight === null || clientSeq !== this.inflightSeq
  }
}

function touchedBlocks(ops: readonly Op[]): Set<string> {
  const blocks = new Set<string>()
  for (const op of ops) {
    blocks.add(op.blockId)
    if (op.op === 'split') {
      blocks.add(op.newBlockId)
    } else if (op.op === 'merge') {
      blocks.add(op.intoId)
    }
  }
  return blocks
}
