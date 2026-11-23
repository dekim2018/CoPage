import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { mergeBlock, splitBlock, textOp } from '../ot/op'
import { movePosition } from './presence'

describe('남의 커서 옮기기', () => {
  it('같은 블록의 앞에 글자가 들어가면 그만큼 뒤로, 지워지면 앞으로 간다', () => {
    const at = { blockId: 'X', offset: 3 }
    expect(movePosition(at, [textOp('X', Delta.builder().retain(1).insert('ab').build())])).toEqual({ blockId: 'X', offset: 5 })
    expect(movePosition(at, [textOp('X', Delta.builder().delete(2).build())])).toEqual({ blockId: 'X', offset: 1 })
    expect(movePosition(at, [textOp('Y', Delta.builder().insert('ab').build())])).toEqual(at)
  })

  it('블록이 커서 앞에서 나뉘면 새 블록으로, 합쳐지면 합쳐진 블록으로 간다', () => {
    const at = { blockId: 'X', offset: 3 }
    expect(movePosition(at, [splitBlock('X', 1, 'N', 'root', 'b', 'paragraph')])).toEqual({ blockId: 'N', offset: 2 })
    expect(movePosition(at, [splitBlock('X', 3, 'N', 'root', 'b', 'paragraph')])).toEqual(at)
    expect(movePosition(at, [mergeBlock('X', 'W', 4)])).toEqual({ blockId: 'W', offset: 7 })
  })

  it('묶음 안의 연산을 차례로 따라간다', () => {
    const ops = [splitBlock('X', 1, 'N', 'root', 'b', 'paragraph'), textOp('N', Delta.builder().insert('!').build())]
    expect(movePosition({ blockId: 'X', offset: 3 }, ops)).toEqual({ blockId: 'N', offset: 3 })
  })
})
