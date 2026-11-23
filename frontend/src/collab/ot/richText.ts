import * as A from './attrs'
import type { Attrs } from './attrs'
import { InvalidArgumentError } from './errors'

/** 같은 서식이 이어지는 글자 구간. */
export interface Segment {
  readonly text: string
  readonly attrs: Attrs
}

export function segment(text: string, attrs: Attrs = A.NO_ATTRS): Segment {
  if (text.length === 0) {
    throw new InvalidArgumentError('빈 구간은 만들 수 없다')
  }
  return Object.freeze({ text, attrs: A.merge(A.NO_ATTRS, A.copy(attrs), false) })
}

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 서식이 있는 글. 바뀌지 않는 값이다. Java 의 `com.copage.ot.text.RichText` 와 같은 규칙이다.
 *
 * 글자 위치는 UTF-16 코드 단위로 센다. JavaScript 문자열의 length 와 Java String 의 length() 가 같은 기준이다.
 */
export class RichText {
  static readonly EMPTY = new RichText([])

  readonly length: number
  private plain: string | undefined

  private constructor(readonly segments: readonly Segment[]) {
    let total = 0
    for (const s of segments) {
      total += s.text.length
    }
    this.length = total
  }

  static of(text: string, attrs: Attrs = A.NO_ATTRS): RichText {
    if (text.length === 0) {
      return RichText.EMPTY
    }
    return new RichText(Object.freeze([segment(text, attrs)]))
  }

  static ofSegments(segments: Iterable<Segment>): RichText {
    const builder = new RichTextBuilder()
    for (const s of segments) {
      builder.append(s.text, s.attrs)
    }
    return builder.build()
  }

  /** 정규화가 끝난 구간 목록으로 만든다. RichTextBuilder 만 쓴다. */
  static fromNormalized(segments: readonly Segment[]): RichText {
    return segments.length === 0 ? RichText.EMPTY : new RichText(Object.freeze(segments.slice()))
  }

  isEmpty(): boolean {
    return this.length === 0
  }

  plainText(): string {
    if (this.plain === undefined) {
      this.plain = this.segments.map((s) => s.text).join('')
    }
    return this.plain
  }

  /** offset 이 이모지처럼 코드 단위 2개를 쓰는 글자의 가운데면 true. 이런 위치는 자를 수 없다. */
  splitsSurrogatePair(offset: number): boolean {
    if (offset <= 0 || offset >= this.length) {
      return false
    }
    const text = this.plainText()
    return isHighSurrogate(text.charCodeAt(offset - 1)) && isLowSurrogate(text.charCodeAt(offset))
  }

  /** [from, to) 구간을 잘라 낸다. */
  slice(from: number, to: number): RichText {
    if (from < 0 || to > this.length || from > to) {
      throw new RangeError(`구간 [${from}, ${to}) 이 길이 ${this.length} 를 벗어난다`)
    }
    if (from === to) {
      return RichText.EMPTY
    }
    if (from === 0 && to === this.length) {
      return this
    }
    const out: Segment[] = []
    let cursor = 0
    for (const s of this.segments) {
      const end = cursor + s.text.length
      if (end > from && cursor < to) {
        const start = Math.max(from, cursor) - cursor
        const stop = Math.min(to, end) - cursor
        out.push(segment(s.text.substring(start, stop), s.attrs))
      }
      cursor = end
      if (cursor >= to) {
        break
      }
    }
    return new RichText(Object.freeze(out))
  }

  concat(other: RichText): RichText {
    if (other.isEmpty()) {
      return this
    }
    if (this.isEmpty()) {
      return other
    }
    return new RichTextBuilder().appendText(this).appendText(other).build()
  }

  /** 모든 글자의 서식에 change 를 덮어쓴다. 값이 null 인 키는 지운다. */
  withAttrs(change: Attrs): RichText {
    if (A.isEmpty(change) || this.isEmpty()) {
      return this
    }
    const builder = new RichTextBuilder()
    for (const s of this.segments) {
      builder.append(s.text, A.merge(s.attrs, change, false))
    }
    return builder.build()
  }

  equals(other: RichText): boolean {
    return (
      this.segments.length === other.segments.length &&
      this.segments.every((s, i) => {
        const o = other.segments[i] as Segment
        return s.text === o.text && A.equals(s.attrs, o.attrs)
      })
    )
  }
}

/** 이웃한 구간의 서식이 같으면 하나로 합치면서 글을 만든다. */
export class RichTextBuilder {
  private readonly out: Segment[] = []

  append(text: string, attrs: Attrs = A.NO_ATTRS): this {
    if (text.length === 0) {
      return this
    }
    const next = segment(text, attrs)
    const last = this.out[this.out.length - 1]
    if (last !== undefined && A.equals(last.attrs, next.attrs)) {
      this.out[this.out.length - 1] = segment(last.text + next.text, last.attrs)
      return this
    }
    this.out.push(next)
    return this
  }

  appendText(text: RichText): this {
    for (const s of text.segments) {
      this.append(s.text, s.attrs)
    }
    return this
  }

  build(): RichText {
    return RichText.fromNormalized(this.out)
  }
}
