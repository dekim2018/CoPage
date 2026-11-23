import { describe, expect, it } from 'vitest'
import { ClientSession } from '../src/collab/client/clientSession'
import type { CommittedChange, Submission } from '../src/collab/client/clientSession'
import { Delta } from '../src/collab/ot/delta'
import type { Document } from '../src/collab/ot/document'
import { InvalidArgumentError, InvalidOperationError, TransformConflictError } from '../src/collab/ot/errors'
import { between } from '../src/collab/ot/fractionalIndex'
import { invert } from '../src/collab/ot/inverter'
import * as J from '../src/collab/ot/json'
import { transform } from '../src/collab/ot/transformer'
import { loadCases, Mismatches, orError } from './vectors'

/*
 * Java 구현(ot-core)이 계산한 (입력, 기대 결과)를 TypeScript 구현으로 다시 계산해 비교한다 (docs/adr/0009, 13.1).
 * 벡터 파일마다 모든 사례를 돌리고, 어긋난 사례는 시드와 함께 앞쪽 몇 건을 보여 준다.
 */

function expectNoMismatch(name: string, total: number, mismatches: Mismatches): void {
  expect(total).toBeGreaterThan(0)
  expect(mismatches.samples, `${name}: ${total}건 중 ${mismatches.count}건이 어긋났다`).toEqual([])
  console.log(`${name}: ${total}건 일치`)
}

interface DeltaCase {
  seed: number
  text: unknown
  a: unknown
  b: unknown
  c?: unknown
  offset: number
  expected: Record<string, unknown>
}

interface DocumentCase {
  seed: number
  start: unknown
}

interface ApplyCase extends DocumentCase {
  ops: unknown
  expected: unknown
}

interface TransformCase extends DocumentCase {
  a: unknown
  b: unknown
  aIsLater: boolean
  expected: unknown
}

interface InvertCase extends DocumentCase {
  change: unknown
  expected: { inverse: unknown; restored: unknown }
}

interface FractionalIndexCase {
  before: string | null
  after: string | null
  expected: unknown
}

interface ClientEvent {
  call: 'edit' | 'poll' | 'change' | 'ack' | 'nack' | 'resync'
  ops?: unknown
  change?: CommittedChangeJson
  missed?: CommittedChangeJson[]
  clientSeq?: number
  rev?: number
  submission?: unknown
  state: { confirmedRev: number; hasPending: boolean; view?: unknown }
}

interface CommittedChangeJson {
  rev: number
  clientId: string
  clientSeq: number
  ops: unknown
}

interface ClientScenario {
  seed: number
  start: unknown
  startRev: number
  clients: { clientId: string; events: ClientEvent[]; end: { confirmed: unknown; view: unknown } }[]
}

describe('적합성 벡터', () => {
  it('텍스트 편집 (delta.json)', () => {
    const cases = loadCases<DeltaCase>('delta.json')
    const mismatches = new Mismatches()
    for (const c of cases) {
      const where = `delta 시드 ${c.seed}`
      const e = c.expected
      const text = J.decodeRichText(c.text)
      const a = Delta.of(J.decodeComponents(c.a))
      const b = J.decodeDelta(c.b)
      mismatches.check(where, 'normalized', e.normalized, J.encodeDelta(a))
      mismatches.check(where, 'baseLength', e.baseLength, a.baseLength())
      mismatches.check(where, 'lengthChange', e.lengthChange, a.lengthChange())
      mismatches.check(where, 'apply', e.apply, orError(() => J.encodeRichText(a.apply(text)), InvalidArgumentError, 'argument'))
      mismatches.check(where, 'transformLater', e.transformLater, J.encodeDelta(a.transform(b, true)))
      mismatches.check(where, 'transformEarlier', e.transformEarlier, J.encodeDelta(a.transform(b, false)))
      mismatches.check(where, 'invert', e.invert, J.encodeDelta(a.invert(text)))
      mismatches.check(where, 'splitAt', e.splitAt, a.splitAt(c.offset).map(J.encodeDelta))
      mismatches.check(where, 'transformPosition', e.transformPosition, a.transformPosition(c.offset))
      mismatches.check(where, 'compose', e.compose, c.c === undefined ? undefined : J.encodeDelta(a.compose(J.decodeDelta(c.c))))
    }
    expectNoMismatch('delta.json', cases.length, mismatches)
  })

  it('문서에 적용 (apply.json)', () => {
    const cases = loadCases<ApplyCase>('apply.json')
    const mismatches = new Mismatches()
    for (const c of cases) {
      const start = J.decodeDocument(c.start)
      const ops = J.decodeOps(c.ops)
      const actual = orError(
        () => {
          const applied = start.apply(ops)
          return { document: J.encodeDocument(applied), visibleOrder: visibleOrder(applied) }
        },
        InvalidOperationError,
        'invalid',
      )
      mismatches.check(`apply 시드 ${c.seed}`, 'result', c.expected, actual)
    }
    expectNoMismatch('apply.json', cases.length, mismatches)
  })

  it('연산 묶음의 변환 (transform.json)', () => {
    const cases = loadCases<TransformCase>('transform.json')
    const mismatches = new Mismatches()
    for (const c of cases) {
      const start = J.decodeDocument(c.start)
      const a = J.decodeOps(c.a)
      const b = J.decodeOps(c.b)
      const actual = orError(
        () => {
          const pair = transform(a, b, c.aIsLater)
          return {
            a: J.encodeOps(pair.a),
            b: J.encodeOps(pair.b),
            afterA: J.encodeDocument(start.apply(a)),
            afterB: J.encodeDocument(start.apply(b)),
            viaA: orError(() => J.encodeDocument(start.apply(a).apply(pair.b)), InvalidOperationError, 'invalid'),
            viaB: orError(() => J.encodeDocument(start.apply(b).apply(pair.a)), InvalidOperationError, 'invalid'),
          }
        },
        TransformConflictError,
        'conflict',
      )
      mismatches.check(`transform 시드 ${c.seed}`, 'result', c.expected, actual)
    }
    expectNoMismatch('transform.json', cases.length, mismatches)
  })

  it('역연산 (invert.json)', () => {
    const cases = loadCases<InvertCase>('invert.json')
    const mismatches = new Mismatches()
    for (const c of cases) {
      const where = `invert 시드 ${c.seed}`
      const start = J.decodeDocument(c.start)
      const change = J.decodeOps(c.change)
      const inverse = invert(change, start)
      mismatches.check(where, 'inverse', c.expected.inverse, J.encodeOps(inverse))
      mismatches.check(where, 'restored', c.expected.restored, J.encodeDocument(start.apply(change).apply(inverse)))
    }
    expectNoMismatch('invert.json', cases.length, mismatches)
  })

  it('순서 키 (fractional-index.json)', () => {
    const cases = loadCases<FractionalIndexCase>('fractional-index.json')
    const mismatches = new Mismatches()
    cases.forEach((c, i) => {
      const actual = orError(() => between(c.before, c.after), InvalidArgumentError, 'argument')
      mismatches.check(`fractional-index ${i}번 (${c.before}, ${c.after})`, 'key', c.expected, actual)
    })
    expectNoMismatch('fractional-index.json', cases.length, mismatches)
  })

  it('클라이언트 상태 기계 (client.json)', () => {
    const scenarios = loadCases<ClientScenario>('client.json')
    const mismatches = new Mismatches()
    let events = 0
    for (const scenario of scenarios) {
      const start = J.decodeDocument(scenario.start)
      for (const client of scenario.clients) {
        events += client.events.length
        replayClient(scenario, client, start, mismatches)
      }
    }
    expectNoMismatch(`client.json (호출 ${events}건)`, scenarios.length, mismatches)
  })
})

/** 화면에 보이는 블록 ID 를 화면 순서대로 늘어놓는다. 삭제 표시된 블록과 그 아래는 빠진다. */
function visibleOrder(doc: Document): string[] {
  const out: string[] = []
  const visit = (id: string): void => {
    if (!doc.isVisible(id)) {
      return
    }
    out.push(id)
    for (const child of doc.children(id)) {
      visit(child.id)
    }
  }
  visit(doc.rootId)
  return out
}

function decodeCommitted(json: CommittedChangeJson): CommittedChange {
  return { rev: json.rev, clientId: json.clientId, clientSeq: json.clientSeq, ops: J.decodeOps(json.ops) }
}

function encodeSubmission(submission: Submission | null): unknown {
  if (submission === null) {
    return null
  }
  return {
    clientId: submission.clientId,
    clientSeq: submission.clientSeq,
    baseRev: submission.baseRev,
    ops: J.encodeOps(submission.ops),
  }
}

/** 클라이언트 하나가 받은 호출을 차례로 재생한다. 한 번 어긋나면 뒤따르는 호출도 줄줄이 어긋나므로 그 클라이언트는 멈춘다. */
function replayClient(
  scenario: ClientScenario,
  client: ClientScenario['clients'][number],
  start: Document,
  mismatches: Mismatches,
): void {
  const session = new ClientSession(client.clientId, start, scenario.startRev)
  let expectedView: unknown = scenario.start
  for (const [i, event] of client.events.entries()) {
    const where = `client 시드 ${scenario.seed} ${client.clientId} ${i}번째 ${event.call}`
    try {
      switch (event.call) {
        case 'edit':
          session.edit(J.decodeOps(event.ops))
          break
        case 'poll':
          if (!mismatches.check(where, 'submission', event.submission, encodeSubmission(session.poll()))) {
            return
          }
          break
        case 'change':
          session.onChange(decodeCommitted(event.change as CommittedChangeJson))
          break
        case 'ack':
          session.onAck(event.clientSeq as number, event.rev as number)
          break
        case 'nack':
          session.onNack(event.clientSeq as number)
          break
        case 'resync': {
          const submission = session.resync((event.missed ?? []).map(decodeCommitted))
          if (!mismatches.check(where, 'submission', event.submission, encodeSubmission(submission))) {
            return
          }
          break
        }
      }
    } catch (e) {
      mismatches.add({ where, field: 'exception', expected: '예외 없음', actual: String(e) })
      return
    }
    if (event.state.view !== undefined) {
      expectedView = event.state.view
    }
    const state = { confirmedRev: session.confirmedRev(), hasPending: session.hasPending() }
    if (
      !mismatches.check(where, 'state', { confirmedRev: event.state.confirmedRev, hasPending: event.state.hasPending }, state) ||
      !mismatches.check(where, 'view', expectedView, J.encodeDocument(session.view()))
    ) {
      return
    }
  }
  const where = `client 시드 ${scenario.seed} ${client.clientId} 끝`
  mismatches.check(where, 'confirmed', client.end.confirmed, J.encodeDocument(session.confirmed()))
  mismatches.check(where, 'view', client.end.view, J.encodeDocument(session.view()))
}
