import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { encodeOps } from '../ot/json'
import { insertBlock, textOp } from '../ot/op'
import type { Op } from '../ot/op'
import { RichText } from '../ot/richText'
import { ClientSession } from './clientSession'
import { MAX_CHANGE_BYTES, takeWithin } from './sendBudget'

const encoder = new TextEncoder()

/** 서버가 받는 change 메시지의 바이트 수. */
function messageBytes(ops: readonly Op[]): number {
  return encoder.encode(JSON.stringify({ t: 'change', clientSeq: 123456, baseRev: 1234567, ops: encodeOps(ops) })).length
}

/** 한도 안의 묶음으로 끝까지 나눈다. */
function drain(ops: readonly Op[], budget = MAX_CHANGE_BYTES): Op[][] {
  const out: Op[][] = []
  let rest = ops.slice()
  while (rest.length > 0) {
    const [head, next] = takeWithin(rest, budget)
    expect(head.length).toBeGreaterThan(0)
    out.push(head)
    rest = next
  }
  return out
}

function base(): Document {
  return Document.create('root').apply([insertBlock('p', 'root', between(null, null), 'paragraph', {}, RichText.of('앞뒤'))])
}

describe('묶음 나누기', () => {
  it('한도 안의 묶음은 그대로 한 번에 보낸다', () => {
    const ops = [textOp('p', Delta.builder().retain(1).insert('가').build())]
    expect(takeWithin(ops)).toEqual([ops, []])
  })

  it.each([
    ['ASCII', 'a'],
    ['한글', '가'],
    ['이모지', '😀'],
    ['이스케이프', '"\\\n'],
  ])('큰 붙여넣기(%s)를 나눠도 차례로 적용한 결과가 같고 메시지마다 64KB 를 넘지 않는다', (_, unit) => {
    const pasted = unit.repeat(Math.ceil(300_000 / unit.length))
    const op = textOp('p', Delta.builder().retain(1).insert(pasted, { bold: true }).delete(1).build())
    const parts = drain([op])

    expect(parts.length).toBeGreaterThan(1)
    for (const part of parts) {
      expect(messageBytes(part)).toBeLessThanOrEqual(65_536)
    }
    expect(parts.reduce((doc, part) => doc.apply(part), base()).equals(base().apply(op))).toBe(true)
  })

  it('서로게이트 쌍을 가르지 않는다', () => {
    const op = textOp('p', Delta.builder().insert('😀'.repeat(50_000)).build())
    for (const part of drain([op], 10_000)) {
      for (const o of part) {
        const json = JSON.stringify(encodeOps([o]))
        expect(json).not.toMatch(/\\ud83d"|"\\ude00/)
        expect(encoder.encode(json).length).toBeGreaterThan(0)
      }
      // 짝 없는 반쪽이 있으면 다시 읽을 때 바뀐다.
      const text = JSON.stringify(encodeOps(part))
      expect(new TextDecoder().decode(encoder.encode(text))).toBe(text)
    }
  })

  it('긴 글을 가진 새 블록은 빈 블록과 텍스트 연산들로 나눈다', () => {
    const text = RichText.of('문단 '.repeat(40_000))
    const op = insertBlock('big', 'root', between(between(null, null), null), 'paragraph', {}, text)
    const parts = drain([op])

    expect(parts.length).toBeGreaterThan(1)
    for (const part of parts) {
      expect(messageBytes(part)).toBeLessThanOrEqual(65_536)
    }
    expect(parts.reduce((doc, part) => doc.apply(part), base()).equals(base().apply(op))).toBe(true)
  })

  it('블록이 많은 붙여넣기는 블록 경계에서 나눈다', () => {
    const ops: Op[] = []
    let pos: string | null = null
    for (let i = 0; i < 3_000; i++) {
      pos = between(pos, null)
      ops.push(insertBlock(`b${i}`, 'root', pos, 'paragraph', {}, RichText.of(`${i} 번째 문단의 글이다`)))
    }
    const parts = drain(ops)

    expect(parts.length).toBeGreaterThan(1)
    expect(parts.flat()).toEqual(ops)
    for (const part of parts) {
      expect(messageBytes(part)).toBeLessThanOrEqual(65_536)
    }
  })
})

describe('클라이언트 상태 기계의 큰 묶음', () => {
  it('ack 마다 다음 조각을 보내고, 모두 확정되면 확정 문서가 화면 문서와 같다', () => {
    const session = new ClientSession('me', base(), 1)
    session.edit([textOp('p', Delta.builder().retain(1).insert('붙'.repeat(100_000)).build())])
    const expected = session.view()

    let rev = 1
    let sent = 0
    for (let submission = session.poll(); submission !== null; submission = session.poll()) {
      expect(messageBytes(submission.ops)).toBeLessThanOrEqual(65_536)
      expect(session.poll()).toBeNull()
      session.onAck(submission.clientSeq, ++rev)
      sent++
    }

    expect(sent).toBeGreaterThan(1)
    expect(session.hasPending()).toBe(false)
    expect(session.confirmed().equals(expected)).toBe(true)
  })
})
