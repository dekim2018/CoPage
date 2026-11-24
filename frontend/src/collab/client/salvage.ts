import type { Document } from '../ot/document'
import { InvalidOperationError, TransformConflictError } from '../ot/errors'
import type { Op } from '../ot/op'
import { transform } from '../ot/transformer'

/*
 * 거부되거나 버려진 내 편집에서 살릴 것을 고른다 (진단 DC-3, docs/review/DECISIONS.md D-27).
 *
 * 블록 단위로 판단한다. 한 블록의 글(text)과 속성(setProps) 편집은 다른 블록의 편집과 위치가 얽히지 않는다. 그래서 그 블록에
 * 구조를 바꾸는 편집(넣기, 나누기, 합치기, 옮기기, 지우기)이 섞이지 않았다면 그 블록의 편집만 따로 떼어 지금 문서에 다시 적용할
 * 수 있다. 구조 편집은 거부된 바로 그 이유(충돌)일 가능성이 커서 다시 보내지 않는다. 살리지 못한 편집의 글은 사용자에게 보인다.
 */

/** 버려진 편집 한 무리. */
export interface Discard {
  /** 차례대로. 각 연산은 앞 연산을 적용한 상태 기준이다. */
  readonly ops: readonly Op[]
  /** ops 가 기준으로 한 상태 뒤에 확정된 다른 사람의 편집. 살리기 전에 이것에 맞춰 변환한다. 이미 맞춰져 있으면 null */
  readonly against: readonly Op[] | null
  /** 이 블록들의 편집은 살리지 않는다. 버려지지 않은 내 다른 편집이 같은 블록의 위치를 바꿔 두었다 */
  readonly blocked: ReadonlySet<string>
}

export interface Salvage {
  /** 지금 화면 문서에 다시 적용할 수 있는 편집. 새 묶음으로 보낸다 */
  readonly kept: Op[]
  /** 살리지 못한 편집 */
  readonly lost: Op[]
}

/** 연산이 건드리는 블록. */
function touched(op: Op): string[] {
  switch (op.op) {
    case 'text':
    case 'setProps':
    case 'deleteBlock':
    case 'moveBlock':
    case 'insertBlock':
      return [op.blockId]
    case 'split':
      return [op.blockId, op.newBlockId]
    case 'merge':
      return [op.blockId, op.intoId]
  }
}

function isLocal(op: Op): boolean {
  return op.op === 'text' || op.op === 'setProps'
}

/**
 * 버려진 편집을 지금 화면 문서(view)에 맞춰 다시 적용해 본다. 블록마다 그 블록의 글과 속성 편집을 차례대로 묶어, against 에
 * 맞춰 변환하고 적용한다. 변환이 충돌하거나 적용에 실패한 블록은 통째로 잃는다.
 */
export function salvage(view: Document, discards: readonly Discard[]): Salvage {
  const kept: Op[] = []
  const lost: Op[] = []
  let current = view
  for (const discard of discards) {
    const lostHere: Op[] = []
    const structural = new Set<string>(discard.blocked)
    for (const op of discard.ops) {
      if (!isLocal(op)) {
        touched(op).forEach((id) => structural.add(id))
      }
    }
    const groups = new Map<string, Op[]>()
    for (const op of discard.ops) {
      const blockId = touched(op)[0] as string
      if (!isLocal(op) || structural.has(blockId)) {
        lostHere.push(op)
        continue
      }
      const group = groups.get(blockId)
      if (group === undefined) {
        groups.set(blockId, [op])
      } else {
        group.push(op)
      }
    }
    for (const [blockId, group] of groups) {
      // 글 편집은 지운 블록에도 적용되지만 보이지 않는다. 그렇게 살리면 보관함에도 안 나와 사용자가 잃은 줄 모른다.
      if (!current.isVisible(blockId)) {
        lostHere.push(...group)
        continue
      }
      try {
        const ops = discard.against === null ? group : transform(group, discard.against, true).a
        current = current.apply(ops)
        kept.push(...ops)
      } catch (e) {
        if (!(e instanceof TransformConflictError) && !(e instanceof InvalidOperationError)) {
          throw e
        }
        lostHere.push(...group)
      }
    }
    // 보관함에 사용자가 친 차례대로 보이도록 원래 차례로 둔다.
    const order = new Map(discard.ops.map((op, i) => [op, i]))
    lost.push(...lostHere.sort((a, b) => (order.get(a) as number) - (order.get(b) as number)))
  }
  return { kept, lost }
}

/** 잃은 편집에서 사용자가 친 글을 모은다. 보관함에 보여 주어 다시 붙여 넣을 수 있게 한다. */
export function lostTexts(ops: readonly Op[]): string[] {
  const texts: string[] = []
  for (const op of ops) {
    if (op.op === 'text') {
      const inserted = op.delta.components.map((c) => (c.kind === 'insert' ? c.text : '')).join('')
      if (inserted.trim() !== '') {
        texts.push(inserted)
      }
    } else if (op.op === 'insertBlock') {
      const text = op.text.plainText()
      if (text.trim() !== '') {
        texts.push(text)
      }
    }
  }
  return texts
}
