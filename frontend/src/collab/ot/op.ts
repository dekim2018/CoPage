import * as A from './attrs'
import type { Attrs, AttrValue } from './attrs'
import type { Delta } from './delta'
import { RichText } from './richText'

/**
 * 문서를 바꾸는 연산 7종. Java 의 `com.copage.ot.op.Op` 와 같다.
 *
 * 위치(offset)로 글자를 가리키는 연산은 text, split, merge 셋이고, 나머지는 블록 ID 만 쓴다. `op` 필드로 종류를 가르고,
 * 종류별 분기는 switch 의 끝에서 assertNever 로 막아 둔다. 연산 종류를 추가하면 빠진 분기가 타입 검사에서 걸린다.
 */
export type Op = TextOp | SplitBlock | MergeBlock | InsertBlock | DeleteBlock | MoveBlock | SetProps

/** 블록 종류를 바꿀 때 setProps 에 쓰는 키. */
export const TYPE_KEY = 'type'

/** 블록 안의 글을 고친다. 삭제 표시된 블록에도 적용된다. */
export interface TextOp {
  readonly op: 'text'
  readonly blockId: string
  readonly delta: Delta
}

/**
 * 블록을 offset 에서 둘로 나눈다. 뒤쪽 글이 새 블록으로 간다.
 *
 * 새 블록의 부모, 순서, 종류, 속성을 전부 연산에 적는다. 나누려던 블록이 그 사이 다른 블록에 합쳐져도 새 블록이 어디에
 * 어떤 모습으로 생길지가 달라지지 않게 하기 위해서다.
 */
export interface SplitBlock {
  readonly op: 'split'
  readonly blockId: string
  readonly offset: number
  readonly newBlockId: string
  readonly parentId: string
  readonly pos: string
  readonly type: string
  readonly props: Attrs
}

/** 블록의 글을 intoId 블록 끝에 붙이고 블록을 비운 뒤 삭제 표시한다. intoLength 는 합치기 직전 intoId 블록의 글 길이다. */
export interface MergeBlock {
  readonly op: 'merge'
  readonly blockId: string
  readonly intoId: string
  readonly intoLength: number
}

/** 새 블록을 넣는다. blockId 는 클라이언트가 만든 새 ID 다. */
export interface InsertBlock {
  readonly op: 'insertBlock'
  readonly blockId: string
  readonly parentId: string
  readonly pos: string
  readonly type: string
  readonly props: Attrs
  readonly text: RichText
}

/** 블록에 삭제 표시를 하거나(deleted = true) 표시를 지워 되살린다(deleted = false). 아래 블록은 함께 숨겨진다. */
export interface DeleteBlock {
  readonly op: 'deleteBlock'
  readonly blockId: string
  readonly deleted: boolean
}

/** 블록의 부모나 형제 사이 순서를 바꾼다. 들여쓰기와 내어쓰기도 이 연산이다. */
export interface MoveBlock {
  readonly op: 'moveBlock'
  readonly blockId: string
  readonly parentId: string
  readonly pos: string
}

/** 블록 속성 하나를 바꾼다. value 가 null 이면 속성을 지운다. key 가 TYPE_KEY 면 블록 종류를 바꾼다. */
export interface SetProps {
  readonly op: 'setProps'
  readonly blockId: string
  readonly key: string
  readonly value: AttrValue
}

export function textOp(blockId: string, delta: Delta): TextOp {
  return Object.freeze({ op: 'text', blockId, delta })
}

export function splitBlock(
  blockId: string,
  offset: number,
  newBlockId: string,
  parentId: string,
  pos: string,
  type: string,
  props: Attrs = A.NO_ATTRS,
): SplitBlock {
  return Object.freeze({ op: 'split', blockId, offset, newBlockId, parentId, pos, type, props: A.copy(props) })
}

/** 같은 새 블록 정보로 나누는 대상과 위치만 바꾼다. */
export function splitAt(split: SplitBlock, blockId: string, offset: number): SplitBlock {
  return splitBlock(blockId, offset, split.newBlockId, split.parentId, split.pos, split.type, split.props)
}

export function mergeBlock(blockId: string, intoId: string, intoLength: number): MergeBlock {
  return Object.freeze({ op: 'merge', blockId, intoId, intoLength })
}

export function insertBlock(
  blockId: string,
  parentId: string,
  pos: string,
  type: string,
  props: Attrs = A.NO_ATTRS,
  text: RichText = RichText.EMPTY,
): InsertBlock {
  return Object.freeze({ op: 'insertBlock', blockId, parentId, pos, type, props: A.copy(props), text })
}

export function deleteBlock(blockId: string, deleted: boolean): DeleteBlock {
  return Object.freeze({ op: 'deleteBlock', blockId, deleted })
}

export function moveBlock(blockId: string, parentId: string, pos: string): MoveBlock {
  return Object.freeze({ op: 'moveBlock', blockId, parentId, pos })
}

export function setProps(blockId: string, key: string, value: AttrValue): SetProps {
  return Object.freeze({ op: 'setProps', blockId, key, value })
}

/** switch 에서 빠진 연산 종류가 있으면 타입 검사에서 걸리게 한다. */
export function assertNever(value: never): never {
  throw new Error(`처리하지 않은 연산: ${JSON.stringify(value)}`)
}
