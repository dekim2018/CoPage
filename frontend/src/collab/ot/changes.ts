import { textOp } from './op'
import type { Op } from './op'

/**
 * 묶음 뒤에 연산을 이어 붙인다. 같은 블록의 텍스트 편집이 맞닿으면 하나로 합성한다. Java 의 `Changes.append` 와 같다.
 *
 * "안녕하세요"를 한 글자씩 치면 텍스트 연산 5개가 생기지만, 아직 보내지 않은 묶음 안에서는 삽입 하나로 줄어든다.
 */
export function append(change: readonly Op[], more: readonly Op[]): Op[] {
  const out = change.slice()
  for (const op of more) {
    const previous = out[out.length - 1]
    if (op.op === 'text' && previous !== undefined && previous.op === 'text' && previous.blockId === op.blockId) {
      const composed = previous.delta.compose(op.delta)
      if (composed.isNoop()) {
        out.pop()
      } else {
        out[out.length - 1] = textOp(op.blockId, composed)
      }
    } else {
      out.push(op)
    }
  }
  return out
}
