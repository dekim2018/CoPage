import * as A from './attrs'
import type { Attrs } from './attrs'
import { InvalidArgumentError } from './errors'
import { RichText, RichTextBuilder } from './richText'

/** 글자를 그대로 두고 지나간다. attrs 가 있으면 그 글자들의 서식을 바꾼다. 값이 null 인 서식은 지운다. */
export interface Retain {
  readonly kind: 'retain'
  readonly count: number
  readonly attrs: Attrs
}

/** 이 자리에 글자를 넣는다. */
export interface Insert {
  readonly kind: 'insert'
  readonly text: string
  readonly attrs: Attrs
}

/** 글자를 지운다. */
export interface Delete {
  readonly kind: 'delete'
  readonly count: number
}

/** 텍스트 연산을 이루는 조각. 블록의 첫 글자부터 차례로 읽으며 적용한다. */
export type Component = Retain | Insert | Delete

export function retain(count: number, attrs: Attrs = A.NO_ATTRS): Retain {
  if (!Number.isInteger(count) || count <= 0) {
    throw new InvalidArgumentError('retain 길이는 1 이상이어야 한다')
  }
  return Object.freeze({ kind: 'retain', count, attrs: A.copy(attrs) })
}

export function insert(text: string, attrs: Attrs = A.NO_ATTRS): Insert {
  if (text.length === 0) {
    throw new InvalidArgumentError('빈 문자열은 삽입할 수 없다')
  }
  return Object.freeze({ kind: 'insert', text, attrs: A.merge(A.NO_ATTRS, A.copy(attrs), false) })
}

export function del(count: number): Delete {
  if (!Number.isInteger(count) || count <= 0) {
    throw new InvalidArgumentError('delete 길이는 1 이상이어야 한다')
  }
  return Object.freeze({ kind: 'delete', count })
}

/** 문서의 글자를 몇 개 소비하는지. 삽입은 0 이다. */
export function baseLengthOf(component: Component): number {
  return component.kind === 'insert' ? 0 : component.count
}

/** 조각 자체의 길이. 삽입은 넣는 글자 수다. */
export function lengthOf(component: Component): number {
  return component.kind === 'insert' ? component.text.length : component.count
}

function componentEquals(a: Component, b: Component): boolean {
  switch (a.kind) {
    case 'retain':
      return b.kind === 'retain' && a.count === b.count && A.equals(a.attrs, b.attrs)
    case 'insert':
      return b.kind === 'insert' && a.text === b.text && A.equals(a.attrs, b.attrs)
    case 'delete':
      return b.kind === 'delete' && a.count === b.count
  }
}

/**
 * 한 블록의 글에 대한 편집. 조각(retain, insert, delete)의 나열이다. Java 의 `com.copage.ot.text.Delta` 와 같은 규칙이다.
 *
 * 항상 정규화된 상태로 만든다. 같은 종류의 이웃 조각은 합치고, 같은 자리의 삽입과 삭제는 삽입을 먼저 두고, 끝에 남는 서식
 * 없는 retain 은 적지 않는다. 그래서 같은 편집은 같은 값이 되고 equals 로 비교할 수 있다.
 */
export class Delta {
  static readonly NOOP = new Delta([])

  private constructor(readonly components: readonly Component[]) {}

  static builder(): DeltaBuilder {
    return new DeltaBuilder()
  }

  static of(components: Iterable<Component>): Delta {
    const builder = new DeltaBuilder()
    for (const component of components) {
      builder.push(component)
    }
    return builder.build()
  }

  /** 정규화가 끝난 조각 목록으로 만든다. DeltaBuilder 만 쓴다. */
  static fromNormalized(components: readonly Component[]): Delta {
    return components.length === 0 ? Delta.NOOP : new Delta(Object.freeze(components.slice()))
  }

  isNoop(): boolean {
    return this.components.length === 0
  }

  /** 이 편집을 적용하려면 글이 최소 몇 글자여야 하는지. */
  baseLength(): number {
    let total = 0
    for (const component of this.components) {
      total += baseLengthOf(component)
    }
    return total
  }

  /** 적용 후 글 길이가 얼마나 바뀌는지 (넣은 글자 수 - 지운 글자 수). */
  lengthChange(): number {
    let change = 0
    for (const component of this.components) {
      if (component.kind === 'insert') {
        change += component.text.length
      } else if (component.kind === 'delete') {
        change -= component.count
      }
    }
    return change
  }

  /** 글에 적용한 결과를 돌려준다. 길이가 맞지 않거나 이모지의 가운데를 자르면 InvalidArgumentError 를 던진다. */
  apply(text: RichText): RichText {
    if (this.baseLength() > text.length) {
      throw new InvalidArgumentError(`편집이 글 길이를 넘는다: 필요 ${this.baseLength()}, 실제 ${text.length}`)
    }
    const out = new RichTextBuilder()
    let cursor = 0
    for (const component of this.components) {
      if (text.splitsSurrogatePair(cursor)) {
        throw new InvalidArgumentError(`코드 단위 2개짜리 글자의 가운데(위치 ${cursor})를 자를 수 없다`)
      }
      switch (component.kind) {
        case 'retain':
          out.appendText(text.slice(cursor, cursor + component.count).withAttrs(component.attrs))
          cursor += component.count
          break
        case 'insert':
          out.append(component.text, component.attrs)
          break
        case 'delete':
          cursor += component.count
          break
      }
    }
    if (text.splitsSurrogatePair(cursor)) {
      throw new InvalidArgumentError(`코드 단위 2개짜리 글자의 가운데(위치 ${cursor})를 자를 수 없다`)
    }
    out.appendText(text.slice(cursor, text.length))
    return out.build()
  }

  /**
   * 같은 글에서 출발한 두 편집 중, 이 편집을 other 가 먼저 적용된 뒤의 글에 맞게 바꾼다.
   *
   * @param thisIsLater 이 편집이 other 보다 나중에 확정되면 true. 같은 자리에 둘 다 삽입하면 먼저 확정된 쪽의 글자가 앞에
   *     오고, 같은 글자의 같은 서식을 둘 다 바꾸면 나중에 확정된 쪽의 값이 남는다.
   */
  transform(other: Delta, thisIsLater: boolean): Delta {
    const mine = new Cursor(this.components)
    const theirs = new Cursor(other.components)
    const out = new DeltaBuilder()
    while (mine.hasNext() || theirs.hasNext()) {
      if (theirs.peek()?.kind === 'insert' && (thisIsLater || mine.peek()?.kind !== 'insert')) {
        out.retain(lengthOf(theirs.next()))
      } else if (mine.peek()?.kind === 'insert') {
        out.push(mine.next())
      } else {
        const length = Math.min(mine.peekLength(), theirs.peekLength())
        const a = mine.next(length)
        const b = theirs.next(length)
        if (b.kind === 'delete') {
          continue
        }
        if (a.kind === 'delete') {
          out.delete(length)
        } else {
          const attrs = (a as Retain).attrs
          const theirAttrs = (b as Retain).attrs
          out.retain(length, thisIsLater ? attrs : A.without(attrs, theirAttrs))
        }
      }
    }
    return out.build()
  }

  /** 이 편집을 적용한 뒤 next 를 적용한 것과 같은 편집 하나를 만든다. */
  compose(next: Delta): Delta {
    const first = new Cursor(this.components)
    const second = new Cursor(next.components)
    const out = new DeltaBuilder()
    while (first.hasNext() || second.hasNext()) {
      if (second.peek()?.kind === 'insert') {
        out.push(second.next())
      } else if (first.peek()?.kind === 'delete') {
        out.push(first.next())
      } else {
        const length = Math.min(first.peekLength(), second.peekLength())
        const a = first.next(length)
        const b = second.next(length)
        if (b.kind === 'retain') {
          if (a.kind === 'retain') {
            out.retain(length, A.merge(a.attrs, b.attrs, true))
          } else {
            const insertA = a as Insert
            out.insert(insertA.text, A.merge(insertA.attrs, b.attrs, false))
          }
        } else if (a.kind === 'retain') {
          out.delete(length)
        }
      }
    }
    return out.build()
  }

  /** 적용 전의 글 base 를 보고, 이 편집을 되돌리는 편집을 만든다. */
  invert(base: RichText): Delta {
    const out = new DeltaBuilder()
    let cursor = 0
    for (const component of this.components) {
      switch (component.kind) {
        case 'retain':
          if (A.isEmpty(component.attrs)) {
            out.retain(component.count)
          } else {
            for (const s of base.slice(cursor, cursor + component.count).segments) {
              out.retain(s.text.length, A.previousValues(s.attrs, component.attrs))
            }
          }
          cursor += component.count
          break
        case 'insert':
          out.delete(component.text.length)
          break
        case 'delete':
          for (const s of base.slice(cursor, cursor + component.count).segments) {
            out.insert(s.text, s.attrs)
          }
          cursor += component.count
          break
      }
    }
    return out.build()
  }

  /**
   * 글이 offset 에서 둘로 나뉠 때 이 편집을 앞부분용과 뒷부분용으로 가른다.
   *
   * 정확히 offset 자리에 넣는 글자는 앞부분의 끝에 남긴다. transformPosition 과 같은 규칙이어야 나누기와 텍스트 편집을 어느
   * 순서로 적용해도 결과가 같다.
   */
  splitAt(offset: number): [Delta, Delta] {
    const head = new DeltaBuilder()
    const tail = new DeltaBuilder()
    let cursor = 0
    for (const component of this.components) {
      if (component.kind === 'insert') {
        ;(cursor <= offset ? head : tail).push(component)
        continue
      }
      const length = component.count
      const inHead = Math.max(0, Math.min(cursor + length, offset) - cursor)
      if (inHead > 0) {
        head.push(resize(component, inHead))
      }
      if (length - inHead > 0) {
        tail.push(resize(component, length - inHead))
      }
      cursor += length
    }
    return [head.build(), tail.build()]
  }

  /** 이 편집이 적용된 뒤 원래 위치 offset 이 어디로 옮겨 가는지. offset 자리의 삽입은 offset 앞에 들어간 것으로 본다. */
  transformPosition(offset: number): number {
    let cursor = 0
    let moved = offset
    for (const component of this.components) {
      if (cursor > offset) {
        break
      }
      switch (component.kind) {
        case 'insert':
          moved += component.text.length
          break
        case 'delete':
          moved -= Math.max(0, Math.min(cursor + component.count, offset) - cursor)
          cursor += component.count
          break
        case 'retain':
          cursor += component.count
          break
      }
    }
    return moved
  }

  /** 앞에 글자 count 개가 더 붙은 글에 적용할 수 있게 옮긴다. 블록이 다른 블록 끝에 합쳐졌을 때 쓴다. */
  shifted(count: number): Delta {
    if (count === 0 || this.isNoop()) {
      return this
    }
    const out = new DeltaBuilder()
    out.retain(count)
    for (const component of this.components) {
      out.push(component)
    }
    return out.build()
  }

  equals(other: Delta): boolean {
    return (
      this.components.length === other.components.length &&
      this.components.every((c, i) => componentEquals(c, other.components[i] as Component))
    )
  }
}

function resize(component: Retain | Delete, length: number): Component {
  return component.kind === 'retain' ? retain(length, component.attrs) : del(length)
}

/** 조각을 차례로 받아 정규화된 편집을 만든다. */
export class DeltaBuilder {
  private readonly out: Component[] = []

  retain(count: number, attrs: Attrs = A.NO_ATTRS): this {
    if (count > 0) {
      this.push(retain(count, attrs))
    }
    return this
  }

  insert(text: string, attrs: Attrs = A.NO_ATTRS): this {
    if (text.length > 0) {
      this.push(insert(text, attrs))
    }
    return this
  }

  delete(count: number): this {
    if (count > 0) {
      this.push(del(count))
    }
    return this
  }

  push(next: Component): this {
    const out = this.out
    const lastIndex = out.length - 1
    const last = out[lastIndex]
    if (last === undefined) {
      out.push(next)
      return this
    }
    if (next.kind === 'delete' && last.kind === 'delete') {
      out[lastIndex] = del(last.count + next.count)
    } else if (next.kind === 'retain' && last.kind === 'retain' && A.equals(last.attrs, next.attrs)) {
      out[lastIndex] = retain(last.count + next.count, next.attrs)
    } else if (next.kind === 'insert') {
      // 같은 자리의 삽입과 삭제는 결과가 같으므로 항상 삽입을 앞에 둔다.
      const at = last.kind === 'delete' ? lastIndex : lastIndex + 1
      const before = out[at - 1]
      if (before !== undefined && before.kind === 'insert' && A.equals(before.attrs, next.attrs)) {
        out[at - 1] = insert(before.text + next.text, next.attrs)
      } else {
        out.splice(at, 0, next)
      }
    } else {
      out.push(next)
    }
    return this
  }

  build(): Delta {
    let end = this.out.length
    while (end > 0) {
      const last = this.out[end - 1] as Component
      if (last.kind !== 'retain' || !A.isEmpty(last.attrs)) {
        break
      }
      end--
    }
    return Delta.fromNormalized(this.out.slice(0, end))
  }
}

/** 조각 목록을 원하는 길이만큼 잘라 가며 읽는다. 다 읽은 뒤에는 서식 없는 retain 이 끝없이 이어지는 것으로 본다. */
class Cursor {
  private index = 0
  private offset = 0

  constructor(private readonly components: readonly Component[]) {}

  hasNext(): boolean {
    return this.index < this.components.length
  }

  peek(): Component | undefined {
    return this.components[this.index]
  }

  peekLength(): number {
    const current = this.components[this.index]
    return current === undefined ? Infinity : lengthOf(current) - this.offset
  }

  next(length: number = Infinity): Component {
    const current = this.components[this.index]
    if (current === undefined) {
      return retain(length)
    }
    const remaining = lengthOf(current) - this.offset
    const take = Math.min(length, remaining)
    const from = this.offset
    if (take === remaining) {
      this.index++
      this.offset = 0
    } else {
      this.offset += take
    }
    switch (current.kind) {
      case 'retain':
        return retain(take, current.attrs)
      case 'delete':
        return del(take)
      case 'insert':
        return insert(current.text.substring(from, from + take), current.attrs)
    }
  }
}
