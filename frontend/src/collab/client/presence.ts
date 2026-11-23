import type { Op } from '../ot/op'

/*
 * 프레즌스 (docs/05-PROTOCOL.md 7.6). 남의 커서는 블록 ID 와 블록 안 위치로 받는다. 커서 메시지가 편집보다 늦게 와도 화면이
 * 어긋나지 않게, 받은 커서는 그 뒤에 들어오는 연산(남의 편집과 내 편집 모두)에 맞춰 여기서 직접 옮긴다.
 */

export interface CursorPosition {
  readonly blockId: string
  readonly offset: number
}

export interface Cursor {
  readonly anchor: CursorPosition
  readonly head: CursorPosition
}

/** 같은 문서를 연 다른 연결. cursor 가 null 이면 아직 커서를 받지 않았거나 편집기를 떠났다. */
export interface Peer {
  readonly clientId: string
  readonly userId: string
  readonly name: string
  readonly color: string
  readonly cursor: Cursor | null
}

/**
 * 연산 묶음이 적용된 뒤 위치가 어디로 가는지.
 *
 * - 같은 블록의 텍스트 편집: 넣거나 지운 글자 수만큼 옮긴다
 * - 블록이 나뉘었고 위치가 나눈 자리보다 뒤: 새 블록으로 간다
 * - 블록이 다른 블록에 합쳐졌다: 합쳐진 블록의 원래 글 뒤로 간다
 * - 블록이 지워졌다: 그대로 둔다(그리지 않는다)
 */
export function movePosition(position: CursorPosition, ops: readonly Op[]): CursorPosition {
  let { blockId, offset } = position
  for (const op of ops) {
    switch (op.op) {
      case 'text':
        if (op.blockId === blockId) {
          offset = op.delta.transformPosition(offset)
        }
        break
      case 'split':
        if (op.blockId === blockId && offset > op.offset) {
          blockId = op.newBlockId
          offset -= op.offset
        }
        break
      case 'merge':
        if (op.blockId === blockId) {
          blockId = op.intoId
          offset += op.intoLength
        }
        break
      default:
        break
    }
  }
  return { blockId, offset }
}

export function moveCursor(cursor: Cursor, ops: readonly Op[]): Cursor {
  return { anchor: movePosition(cursor.anchor, ops), head: movePosition(cursor.head, ops) }
}
