import * as A from './attrs'
import type { Attrs } from './attrs'
import { InvalidArgumentError, InvalidOperationError } from './errors'
import { assertNever, TYPE_KEY } from './op'
import type { InsertBlock, MergeBlock, MoveBlock, Op, SetProps, SplitBlock, TextOp } from './op'
import { RichText } from './richText'

/**
 * 문서를 이루는 블록 하나. 바뀌지 않는 값이다.
 *
 * parentId 는 부모 블록이고 루트 블록만 null 이다. pos 는 형제 사이 순서를 정하는 키로, 문자열 순서로 비교하고 같으면 블록
 * ID 순서를 따른다. deleted 는 삭제 표시다. 표시된 블록과 그 아래는 화면에 보이지 않지만 문서에는 남는다.
 */
export interface Block {
  readonly id: string
  readonly type: string
  readonly props: Attrs
  readonly text: RichText
  readonly parentId: string | null
  readonly pos: string
  readonly deleted: boolean
}

export function block(
  id: string,
  type: string,
  props: Attrs,
  text: RichText,
  parentId: string | null,
  pos: string,
  deleted: boolean,
): Block {
  return Object.freeze({ id, type, props: A.copy(props), text, parentId, pos, deleted })
}

function withChanges(b: Block, changes: Partial<Omit<Block, 'id'>>): Block {
  const next = { ...b, ...changes }
  return block(next.id, next.type, next.props, next.text, next.parentId, next.pos, next.deleted)
}

function blockEquals(a: Block, b: Block): boolean {
  return (
    a.id === b.id &&
    a.type === b.type &&
    A.equals(a.props, b.props) &&
    a.text.equals(b.text) &&
    a.parentId === b.parentId &&
    a.pos === b.pos &&
    a.deleted === b.deleted
  )
}

function siblingOrder(a: Block, b: Block): number {
  return A.compareStrings(a.pos, b.pos) || A.compareStrings(a.id, b.id)
}

export const ROOT_TYPE = 'page'

/**
 * 페이지 하나의 내용. 블록 트리다. Java 의 `com.copage.ot.model.Document` 와 같은 규칙이다.
 *
 * 블록은 ID 로 찾고, 트리 모양은 각 블록의 부모 ID 와 순서 키로 표현한다. 자식 목록을 따로 들고 있지 않아서 블록을 옮기거나
 * 끼워 넣어도 그 블록 하나만 바뀐다.
 *
 * apply 는 원본을 건드리지 않고 새 문서를 돌려준다. 묶음 안의 연산 하나라도 실패하면 예외가 나고 원본은 그대로다.
 */
export class Document {
  /**
   * 부모 ID 별 자식(삭제 표시 포함, 화면 순서). 처음 children 을 부를 때 한 번 만든다. 문서는 바뀌지 않으므로(apply 는 새 문서를
   * 돌려준다) 만든 뒤에는 그대로 쓴다. 전에는 children 을 부를 때마다 블록 전체를 훑어서, 화면을 그리는 flatten 이 블록 수의
   * 제곱만큼 걸렸다(진단 PERF-1).
   */
  private childIndex: Map<string, readonly Block[]> | null = null

  private constructor(
    readonly rootId: string,
    private readonly blockMap: Map<string, Block>,
  ) {}

  /** 제목이 빈 루트 블록 하나만 있는 새 문서. */
  static create(rootId: string): Document {
    return new Document(rootId, new Map([[rootId, block(rootId, ROOT_TYPE, A.NO_ATTRS, RichText.EMPTY, null, '', false)]]))
  }

  /** 서버가 보낸 스냅샷처럼 이미 있는 블록들로 문서를 만든다. 루트 블록이 있어야 한다. */
  static of(rootId: string, blocks: Iterable<Block>): Document {
    const map = new Map<string, Block>()
    for (const b of blocks) {
      if (map.has(b.id)) {
        throw new InvalidArgumentError(`블록 ${b.id} 이 두 번 있다`)
      }
      map.set(b.id, b)
    }
    const root = map.get(rootId)
    if (root === undefined || root.parentId !== null) {
      throw new InvalidArgumentError(`루트 블록 ${rootId} 이 없다`)
    }
    return new Document(rootId, map)
  }

  get(blockId: string): Block | undefined {
    return this.blockMap.get(blockId)
  }

  contains(blockId: string): boolean {
    return this.blockMap.has(blockId)
  }

  blockCount(): number {
    return this.blockMap.size
  }

  blocks(): IterableIterator<Block> {
    return this.blockMap.values()
  }

  /** 삭제 표시된 것까지 포함한 자식 블록을 화면 순서대로 돌려준다. 돌려준 배열은 고쳐도 문서에 영향이 없다. */
  children(parentId: string): Block[] {
    return [...(this.index().get(parentId) ?? [])]
  }

  private index(): Map<string, readonly Block[]> {
    if (this.childIndex === null) {
      const index = new Map<string, Block[]>()
      for (const b of this.blockMap.values()) {
        if (b.parentId !== null) {
          const siblings = index.get(b.parentId)
          if (siblings === undefined) {
            index.set(b.parentId, [b])
          } else {
            siblings.push(b)
          }
        }
      }
      for (const siblings of index.values()) {
        siblings.sort(siblingOrder)
      }
      this.childIndex = index
    }
    return this.childIndex
  }

  /** 블록 자신과 모든 조상에 삭제 표시가 없으면 true. */
  isVisible(blockId: string): boolean {
    let b = this.blockMap.get(blockId)
    while (b !== undefined) {
      if (b.deleted) {
        return false
      }
      if (b.parentId === null) {
        return true
      }
      b = this.blockMap.get(b.parentId)
    }
    return false
  }

  /** candidateId 가 ancestorId 와 같거나 그 아래에 있으면 true. */
  isSelfOrDescendant(candidateId: string, ancestorId: string): boolean {
    let b = this.blockMap.get(candidateId)
    while (b !== undefined) {
      if (b.id === ancestorId) {
        return true
      }
      b = b.parentId === null ? undefined : this.blockMap.get(b.parentId)
    }
    return false
  }

  /** 연산 묶음(또는 연산 하나)을 적용한 새 문서를 돌려준다. 하나라도 적용할 수 없으면 InvalidOperationError 를 던진다. */
  apply(ops: readonly Op[] | Op): Document {
    const list = Array.isArray(ops) ? (ops as readonly Op[]) : [ops as Op]
    if (list.length === 0) {
      return this
    }
    const next = new Document(this.rootId, new Map(this.blockMap))
    for (const op of list) {
      next.applyInPlace(op)
    }
    return next
  }

  /** 삭제 표시된 블록까지 포함해 모든 블록이 같으면 같은 문서다. */
  equals(other: Document): boolean {
    if (this.rootId !== other.rootId || this.blockMap.size !== other.blockMap.size) {
      return false
    }
    for (const [id, b] of this.blockMap) {
      const o = other.blockMap.get(id)
      if (o === undefined || !blockEquals(b, o)) {
        return false
      }
    }
    return true
  }

  private applyInPlace(op: Op): void {
    switch (op.op) {
      case 'text':
        return this.applyText(op)
      case 'split':
        return this.applySplit(op)
      case 'merge':
        return this.applyMerge(op)
      case 'insertBlock':
        return this.applyInsert(op)
      case 'deleteBlock': {
        const b = this.requireNonRoot(op.blockId, '삭제')
        this.blockMap.set(b.id, withChanges(b, { deleted: op.deleted }))
        return
      }
      case 'moveBlock':
        return this.applyMove(op)
      case 'setProps':
        return this.applySetProps(op)
      default:
        return assertNever(op)
    }
  }

  private applyText(op: TextOp): void {
    const b = this.require(op.blockId)
    try {
      this.blockMap.set(b.id, withChanges(b, { text: op.delta.apply(b.text) }))
    } catch (e) {
      if (e instanceof InvalidArgumentError) {
        throw new InvalidOperationError(`블록 ${b.id} 의 텍스트 편집 실패: ${e.message}`)
      }
      throw e
    }
  }

  private applySplit(op: SplitBlock): void {
    const b = this.requireNonRoot(op.blockId, '나누기')
    if (b.deleted) {
      throw new InvalidOperationError(`삭제된 블록 ${b.id} 은 나눌 수 없다`)
    }
    const text = b.text
    if (op.offset < 0 || op.offset > text.length) {
      throw new InvalidOperationError(`나눌 위치 ${op.offset} 이 글 길이 ${text.length} 를 벗어난다`)
    }
    if (text.splitsSurrogatePair(op.offset)) {
      throw new InvalidOperationError('코드 단위 2개짜리 글자의 가운데는 나눌 수 없다')
    }
    this.requireNewBlock(op.newBlockId, op.parentId, op.pos, op.type)
    this.blockMap.set(b.id, withChanges(b, { text: text.slice(0, op.offset) }))
    this.blockMap.set(
      op.newBlockId,
      block(op.newBlockId, op.type, op.props, text.slice(op.offset, text.length), op.parentId, op.pos, false),
    )
  }

  private applyMerge(op: MergeBlock): void {
    if (op.blockId === op.intoId) {
      throw new InvalidOperationError('블록을 자기 자신에 합칠 수 없다')
    }
    const source = this.requireNonRoot(op.blockId, '합치기')
    const target = this.requireNonRoot(op.intoId, '합치기')
    if (source.deleted || target.deleted) {
      throw new InvalidOperationError('삭제된 블록은 합칠 수 없다')
    }
    if (target.text.length !== op.intoLength) {
      throw new InvalidOperationError(`합칠 대상의 글 길이가 다르다: 연산 ${op.intoLength}, 실제 ${target.text.length}`)
    }
    for (const b of this.blockMap.values()) {
      if (b.parentId === source.id && !b.deleted) {
        throw new InvalidOperationError(`자식 블록이 남아 있는 블록 ${source.id} 은 합칠 수 없다`)
      }
    }
    this.blockMap.set(target.id, withChanges(target, { text: target.text.concat(source.text) }))
    this.blockMap.set(source.id, withChanges(source, { text: RichText.EMPTY, deleted: true }))
  }

  private applyInsert(op: InsertBlock): void {
    this.requireNewBlock(op.blockId, op.parentId, op.pos, op.type)
    this.blockMap.set(op.blockId, block(op.blockId, op.type, op.props, op.text, op.parentId, op.pos, false))
  }

  private applyMove(op: MoveBlock): void {
    const b = this.requireNonRoot(op.blockId, '이동')
    this.require(op.parentId)
    if (op.pos == null) {
      throw new InvalidOperationError('순서 키가 없다')
    }
    if (this.isSelfOrDescendant(op.parentId, b.id)) {
      throw new InvalidOperationError(`블록 ${b.id} 을 자기 자신이나 자기 아래로 옮길 수 없다`)
    }
    this.blockMap.set(b.id, withChanges(b, { parentId: op.parentId, pos: op.pos }))
  }

  private applySetProps(op: SetProps): void {
    const b = this.require(op.blockId)
    if (op.key == null || op.key.length === 0) {
      throw new InvalidOperationError('속성 이름이 없다')
    }
    if (op.key === TYPE_KEY) {
      if (b.parentId === null) {
        throw new InvalidOperationError('루트 블록의 종류는 바꿀 수 없다')
      }
      const type = op.value
      if (typeof type !== 'string' || type.length === 0 || type === ROOT_TYPE) {
        throw new InvalidOperationError(`블록 종류가 올바르지 않다: ${JSON.stringify(op.value)}`)
      }
      this.blockMap.set(b.id, withChanges(b, { type }))
      return
    }
    const props = new Map(Object.entries(b.props))
    if (op.value === null) {
      props.delete(op.key)
    } else {
      props.set(op.key, op.value)
    }
    this.blockMap.set(b.id, withChanges(b, { props: Object.fromEntries(props) }))
  }

  private require(blockId: string | null): Block {
    const b = blockId == null ? undefined : this.blockMap.get(blockId)
    if (b === undefined) {
      throw new InvalidOperationError(`블록 ${blockId} 이 없다`)
    }
    return b
  }

  private requireNonRoot(blockId: string, action: string): Block {
    const b = this.require(blockId)
    if (b.parentId === null) {
      throw new InvalidOperationError(`루트 블록에는 ${action} 연산을 쓸 수 없다`)
    }
    return b
  }

  private requireNewBlock(newBlockId: string, parentId: string, pos: string, type: string): void {
    if (newBlockId == null || newBlockId.length === 0) {
      throw new InvalidOperationError('새 블록 ID 가 없다')
    }
    if (this.blockMap.has(newBlockId)) {
      throw new InvalidOperationError(`블록 ${newBlockId} 이 이미 있다`)
    }
    this.require(parentId)
    if (pos == null) {
      throw new InvalidOperationError('순서 키가 없다')
    }
    if (type == null || type.length === 0 || type === ROOT_TYPE) {
      throw new InvalidOperationError(`블록 종류가 올바르지 않다: ${type}`)
    }
  }
}
