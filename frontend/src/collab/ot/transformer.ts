import { TransformConflictError } from './errors'
import { assertNever, insertBlock, mergeBlock, splitAt, textOp } from './op'
import type { DeleteBlock, InsertBlock, MergeBlock, MoveBlock, Op, SetProps, SplitBlock, TextOp } from './op'
import { RichText } from './richText'

/*
 * 같은 문서에서 출발한 두 연산을 서로에게 맞게 바꾼다. Java 의 `com.copage.ot.Transformer` 와 같은 규칙이다.
 *
 * 서버가 문서마다 하나의 확정 순서를 만들기 때문에, 여기서 지켜야 할 성질은 하나다. 문서 S 에서 출발한 연산 a 와 b 에
 * 대해 "a 를 적용한 뒤 변환된 b 를 적용한 결과"와 "b 를 적용한 뒤 변환된 a 를 적용한 결과"가 같아야 한다(TP1).
 *
 * 둘 중 누가 나중에 확정되는지를 aIsLater 로 받는다. 같은 자리에 둘 다 글자를 넣거나 같은 값을 둘 다 바꾸는 경우처럼 한쪽을
 * 골라야 할 때, 나중에 확정되는 쪽의 값이 남고 먼저 확정되는 쪽의 글자가 앞에 온다.
 */

/** transform 의 결과. a 는 b 뒤에 적용할 수 있게 바뀐 a 묶음, b 는 a 뒤에 적용할 수 있게 바뀐 b 묶음이다. */
export interface TransformPair {
  readonly a: readonly Op[]
  readonly b: readonly Op[]
}

/**
 * 연산 묶음 두 개를 서로에게 맞게 바꾼다.
 *
 * @throws TransformConflictError 두 연산을 변환으로 풀 수 없을 때
 */
export function transform(a: readonly Op[], b: readonly Op[], aIsLater: boolean): TransformPair {
  if (a.length === 0 || b.length === 0) {
    return { a, b }
  }
  const firstA = a[0] as Op
  const firstB = b[0] as Op
  if (a.length === 1 && b.length === 1) {
    return { a: transformOp(firstA, firstB, aIsLater), b: transformOp(firstB, firstA, !aIsLater) }
  }
  if (a.length > 1) {
    const head = transform([firstA], b, aIsLater)
    const rest = transform(a.slice(1), head.b, aIsLater)
    return { a: [...head.a, ...rest.a], b: rest.b }
  }
  const head = transform(a, [firstB], aIsLater)
  const rest = transform(head.a, b.slice(1), aIsLater)
  return { a: rest.a, b: [...head.b, ...rest.b] }
}

/**
 * 연산 a 를, b 가 먼저 적용된 뒤의 문서에 맞게 바꾼다. 결과는 0개 이상의 연산이다.
 *
 * @throws TransformConflictError 두 연산을 변환으로 풀 수 없을 때
 */
export function transformOp(a: Op, b: Op, aIsLater: boolean): Op[] {
  switch (a.op) {
    case 'text':
      return transformText(a, b, aIsLater)
    case 'split':
      return transformSplit(a, b, aIsLater)
    case 'merge':
      return transformMerge(a, b)
    case 'insertBlock':
      return transformInsert(a, b)
    case 'deleteBlock':
      return transformDelete(a, b, aIsLater)
    case 'moveBlock':
      return transformMove(a, b, aIsLater)
    case 'setProps':
      return transformSetProps(a, b, aIsLater)
    default:
      return assertNever(a)
  }
}

function transformText(a: TextOp, b: Op, aIsLater: boolean): Op[] {
  if (b.op === 'text' && b.blockId === a.blockId) {
    const delta = a.delta.transform(b.delta, aIsLater)
    return delta.isNoop() ? [] : [textOp(a.blockId, delta)]
  }
  if (b.op === 'split' && b.blockId === a.blockId) {
    // 블록이 나뉘었다. 편집을 앞 블록 몫과 새 블록 몫으로 가른다.
    const [head, tail] = a.delta.splitAt(b.offset)
    const out: Op[] = []
    if (!head.isNoop()) {
      out.push(textOp(a.blockId, head))
    }
    if (!tail.isNoop()) {
      out.push(textOp(b.newBlockId, tail))
    }
    return out
  }
  if (b.op === 'merge' && b.blockId === a.blockId) {
    // 블록이 다른 블록 끝에 붙었다. 그 블록의 원래 길이만큼 뒤로 민다.
    return [textOp(b.intoId, a.delta.shifted(b.intoLength))]
  }
  return [a]
}

function transformSplit(a: SplitBlock, b: Op, aIsLater: boolean): Op[] {
  switch (b.op) {
    case 'text':
      if (b.blockId === a.blockId) {
        return [splitAt(a, a.blockId, b.delta.transformPosition(a.offset))]
      }
      return [a]
    case 'split':
      requireDifferentNewBlock(a.newBlockId, b.newBlockId)
      if (b.blockId !== a.blockId || a.offset < b.offset) {
        return [a]
      }
      if (a.offset > b.offset) {
        return [splitAt(a, b.newBlockId, a.offset - b.offset)]
      }
      // 두 사람이 같은 자리에서 Enter 를 눌렀다. 먼저 확정된 쪽의 새 블록이 뒤쪽 글을 갖고,
      // 나중 쪽의 새 블록은 빈 줄이 된다.
      if (aIsLater) {
        return [insertBlock(a.newBlockId, a.parentId, a.pos, a.type, a.props, RichText.EMPTY)]
      }
      return [splitAt(a, b.newBlockId, 0)]
    case 'merge':
      if (b.blockId === a.parentId) {
        throw conflict('새 블록이 놓일 부모가 다른 블록에 합쳐졌다')
      }
      if (b.blockId === a.blockId) {
        return [splitAt(a, b.intoId, a.offset + b.intoLength)]
      }
      return [a]
    case 'insertBlock':
      requireDifferentNewBlock(a.newBlockId, b.blockId)
      return [a]
    case 'deleteBlock':
      if (b.blockId === a.blockId) {
        throw conflict('나누려는 블록이 삭제되었다')
      }
      return [a]
    case 'moveBlock':
    case 'setProps':
      return [a]
    default:
      return assertNever(b)
  }
}

function transformMerge(a: MergeBlock, b: Op): Op[] {
  const source = a.blockId
  const target = a.intoId
  switch (b.op) {
    case 'text':
      if (b.blockId === target) {
        return [mergeBlock(source, target, a.intoLength + b.delta.lengthChange())]
      }
      return [a]
    case 'split':
      if (source === b.parentId) {
        throw conflict('합쳐질 블록 아래에 새 블록이 생겼다')
      }
      if (b.blockId === target) {
        // 붙일 대상이 나뉘었다. 뒤쪽 글을 가진 새 블록의 끝에 붙인다.
        return [mergeBlock(source, b.newBlockId, a.intoLength - b.offset)]
      }
      return [a]
    case 'merge':
      if (b.blockId === source) {
        if (b.intoId === target) {
          return []
        }
        throw conflict('같은 블록을 서로 다른 블록에 합치려 했다')
      }
      if (b.blockId === target) {
        if (b.intoId === source) {
          throw conflict('두 블록을 서로에게 합치려 했다')
        }
        // 붙일 대상이 또 다른 블록에 합쳐졌다. 그 블록의 끝에 붙인다.
        return [mergeBlock(source, b.intoId, b.intoLength + a.intoLength)]
      }
      if (b.intoId === target) {
        throw conflict('서로 다른 두 블록을 같은 블록에 합치려 했다')
      }
      return [a]
    case 'insertBlock':
      if (source === b.parentId) {
        throw conflict('합쳐질 블록 아래에 새 블록이 생겼다')
      }
      return [a]
    case 'deleteBlock':
      if (b.blockId === source || b.blockId === target) {
        throw conflict('합치려는 블록이 삭제되었다')
      }
      return [a]
    case 'moveBlock':
      if (source === b.parentId) {
        throw conflict('합쳐질 블록 아래로 다른 블록이 옮겨졌다')
      }
      return [a]
    case 'setProps':
      return [a]
    default:
      return assertNever(b)
  }
}

function transformInsert(a: InsertBlock, b: Op): Op[] {
  switch (b.op) {
    case 'split':
      requireDifferentNewBlock(a.blockId, b.newBlockId)
      break
    case 'insertBlock':
      requireDifferentNewBlock(a.blockId, b.blockId)
      break
    case 'merge':
      if (b.blockId === a.parentId) {
        throw conflict('새 블록이 놓일 부모가 다른 블록에 합쳐졌다')
      }
      break
    case 'text':
    case 'deleteBlock':
    case 'moveBlock':
    case 'setProps':
      break
    default:
      return assertNever(b)
  }
  return [a]
}

function transformDelete(a: DeleteBlock, b: Op, aIsLater: boolean): Op[] {
  if (b.op === 'deleteBlock' && b.blockId === a.blockId) {
    return aIsLater ? [a] : []
  }
  if (b.op === 'split' && b.blockId === a.blockId) {
    throw conflict('삭제하려는 블록이 나뉘었다')
  }
  if (b.op === 'merge' && (b.blockId === a.blockId || b.intoId === a.blockId)) {
    throw conflict('삭제하려는 블록이 합쳐졌다')
  }
  return [a]
}

function transformMove(a: MoveBlock, b: Op, aIsLater: boolean): Op[] {
  if (b.op === 'moveBlock' && b.blockId === a.blockId) {
    return aIsLater ? [a] : []
  }
  if (b.op === 'merge' && b.blockId === a.parentId) {
    throw conflict('옮겨 갈 부모가 다른 블록에 합쳐졌다')
  }
  return [a]
}

function transformSetProps(a: SetProps, b: Op, aIsLater: boolean): Op[] {
  if (b.op === 'setProps' && b.blockId === a.blockId && b.key === a.key) {
    return aIsLater ? [a] : []
  }
  return [a]
}

function requireDifferentNewBlock(mine: string, theirs: string): void {
  if (mine === theirs) {
    throw conflict('두 연산이 같은 ID 로 새 블록을 만들려 했다')
  }
}

function conflict(message: string): TransformConflictError {
  return new TransformConflictError(message)
}
