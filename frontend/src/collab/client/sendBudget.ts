import { DeltaBuilder, insert } from '../ot/delta'
import type { Component } from '../ot/delta'
import { encodeOp } from '../ot/json'
import { insertBlock, textOp } from '../ot/op'
import type { Op, TextOp } from '../ot/op'
import { RichText } from '../ot/richText'

/**
 * 묶음 하나에 실을 연산의 JSON 크기 한도(바이트). 서버는 change 메시지가 64KB(65,536 바이트)를 넘으면 4008 로 끊는다
 * (docs/05-PROTOCOL.md 7.7). 메시지 겉(t, clientSeq, baseRev)이 들어갈 자리를 남긴다.
 */
export const MAX_CHANGE_BYTES = 60_000

const encoder = new TextEncoder()

/** 연산 하나를 규약 JSON 으로 적었을 때의 바이트 수. */
export function bytesOf(op: Op): number {
  return encoder.encode(JSON.stringify(encodeOp(op))).length
}

/**
 * 묶음의 앞에서부터 한도 안에 드는 만큼을 떼어 낸다. 연산 하나가 한도를 넘으면(큰 붙여넣기) 차례로 적용하면 같은 결과가
 * 되는 작은 연산들로 나눈 뒤 뗀다. 앞부분은 비지 않는다(나눌 수 없는 연산 하나가 한도를 넘어도 그대로 보낸다).
 *
 * 앞부분과 뒷부분을 차례로 적용한 결과는 원래 묶음을 적용한 결과와 같다.
 */
export function takeWithin(ops: readonly Op[], budget: number = MAX_CHANGE_BYTES): [Op[], Op[]] {
  const queue = ops.slice()
  const head: Op[] = []
  let used = 2
  while (queue.length > 0) {
    const op = queue[0] as Op
    const size = bytesOf(op) + 1
    if (size > budget) {
      const pieces = splitOp(op, budget)
      if (pieces.length > 1) {
        queue.splice(0, 1, ...pieces)
        continue
      }
    }
    if (head.length > 0 && used + size > budget) {
      break
    }
    head.push(queue.shift() as Op)
    used += size
  }
  return [head, queue]
}

/** 한도를 넘는 연산을 나눈다. 나눌 수 없는 연산은 그대로 하나를 돌려준다. */
function splitOp(op: Op, budget: number): Op[] {
  switch (op.op) {
    case 'text':
      return splitText(op, budget)
    case 'insertBlock': {
      if (op.text.isEmpty()) {
        return [op]
      }
      // 빈 블록을 넣고 글은 텍스트 연산으로 붙인다. 그 텍스트 연산은 다음 차례에 다시 나뉜다.
      const fill = new DeltaBuilder()
      for (const s of op.text.segments) {
        fill.insert(s.text, s.attrs)
      }
      return [
        insertBlock(op.blockId, op.parentId, op.pos, op.type, op.props, RichText.EMPTY),
        textOp(op.blockId, fill.build()),
      ]
    }
    default:
      return [op]
  }
}

/** 조각 하나(삽입 제외)와 서식이 JSON 에서 차지하는 대강의 바이트. 정확할 필요는 없고 넉넉하면 된다. */
function overheadOf(component: Component): number {
  const attrs = component.kind === 'delete' ? 0 : encoder.encode(JSON.stringify(component.attrs)).length
  return 40 + attrs
}

/** JSON 문자열 안에서 글자 하나(UTF-16 단위)가 차지하는 바이트. 이스케이프를 넉넉히 센다. */
function charBytes(code: number): number {
  if (code < 0x20) {
    return 6
  }
  if (code === 0x22 || code === 0x5c) {
    return 2
  }
  if (code < 0x80) {
    return 1
  }
  if (code < 0x800) {
    return 2
  }
  // 서로게이트 쌍의 두 반쪽은 합쳐 4 바이트다. 각각 3 으로 세어 넉넉하게 둔다.
  return 3
}

/**
 * 텍스트 연산을 차례로 적용할 작은 연산들로 나눈다. 앞 조각들이 만든 결과 글자 수(retain 과 삽입)만큼을 다음 조각 앞에
 * retain 으로 두면, 앞 조각을 적용한 글 위에서 원래 위치를 그대로 가리킨다. 큰 삽입은 서로게이트 쌍을 가르지 않고 자른다.
 */
function splitText(op: TextOp, budget: number): TextOp[] {
  const limit = budget - 200
  const pieces: TextOp[] = []
  let current: Component[] = []
  let currentBytes = 0
  let currentOutput = 0
  let outputBefore = 0

  const finish = (): void => {
    if (current.length === 0) {
      return
    }
    const builder = new DeltaBuilder()
    if (outputBefore > 0) {
      builder.retain(outputBefore)
    }
    for (const component of current) {
      builder.push(component)
    }
    pieces.push(textOp(op.blockId, builder.build()))
    outputBefore += currentOutput
    current = []
    currentBytes = 0
    currentOutput = 0
  }

  for (const component of op.delta.components) {
    const overhead = overheadOf(component)
    if (component.kind !== 'insert') {
      if (current.length > 0 && currentBytes + overhead > limit) {
        finish()
      }
      current.push(component)
      currentBytes += overhead
      currentOutput += component.kind === 'retain' ? component.count : 0
      continue
    }
    const text = component.text
    let start = 0
    while (start < text.length) {
      const room = limit - currentBytes - overhead
      let used = 0
      let end = start
      while (end < text.length) {
        const size = charBytes(text.charCodeAt(end))
        if (used + size > room) {
          break
        }
        used += size
        end++
      }
      if (end < text.length && end > start && isHighSurrogate(text.charCodeAt(end - 1))) {
        end--
      }
      if (end === start) {
        if (current.length > 0) {
          finish()
          continue
        }
        // 빈 조각에도 글자 하나가 들지 않을 만큼 한도가 작다. 코드 포인트 하나를 그대로 싣는다.
        end = start + (isHighSurrogate(text.charCodeAt(start)) ? 2 : 1)
      }
      current.push(insert(text.substring(start, end), component.attrs))
      currentBytes += overhead + used
      currentOutput += end - start
      start = end
      if (start < text.length) {
        finish()
      }
    }
  }
  finish()
  return pieces.length === 0 ? [op] : pieces
}

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}
