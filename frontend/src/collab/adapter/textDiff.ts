import * as A from '../ot/attrs'
import { Delta } from '../ot/delta'
import type { RichText } from '../ot/richText'

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 두 글의 차이를 텍스트 편집 하나로 만든다. 앞뒤로 같은 부분을 남기고 가운데를 바꾼다.
 *
 * 편집기는 한 번의 트랜잭션에서 보통 한 군데만 바꾸므로(타이핑, 붙여넣기, 한글 조합) 이 방식으로 충분하다. 이모지처럼 코드 단위
 * 2개짜리 글자의 가운데에서 자르지 않는다. 서버는 그런 편집을 거부한다.
 */
export function diffText(before: string, after: string): Delta {
  if (before === after) {
    return Delta.NOOP
  }
  const limit = Math.min(before.length, after.length)
  let prefix = 0
  while (prefix < limit && before.charCodeAt(prefix) === after.charCodeAt(prefix)) {
    prefix++
  }
  if (prefix > 0 && isHighSurrogate(before.charCodeAt(prefix - 1))) {
    prefix--
  }
  let suffix = 0
  while (
    suffix < limit - prefix &&
    before.charCodeAt(before.length - 1 - suffix) === after.charCodeAt(after.length - 1 - suffix)
  ) {
    suffix++
  }
  if (suffix > 0 && isLowSurrogate(before.charCodeAt(before.length - suffix))) {
    suffix--
  }
  return Delta.builder()
    .retain(prefix)
    .delete(before.length - prefix - suffix)
    .insert(after.slice(prefix, after.length - suffix))
    .build()
}

/** 글자 하나하나의 서식. 구간 경계와 상관없이 위치로 비교하려고 쓴다. */
function attrsPerUnit(text: RichText): A.Attrs[] {
  const out: A.Attrs[] = []
  for (const segment of text.segments) {
    for (let i = 0; i < segment.text.length; i++) {
      out.push(segment.attrs)
    }
  }
  return out
}

/** before 의 서식을 after 로 바꾸는 변경. 없어진 서식은 null(지우기)이다. */
function attrChange(before: A.Attrs, after: A.Attrs): A.Attrs {
  const change: Record<string, A.AttrValue> = {}
  for (const [key, value] of Object.entries(after)) {
    if (!A.has(before, key) || !A.valueEquals(before[key], value)) {
      change[key] = value
    }
  }
  for (const key of Object.keys(before)) {
    if (!A.has(after, key)) {
      change[key] = null
    }
  }
  return A.copy(change)
}

/**
 * 서식 있는 두 글의 차이를 텍스트 편집 하나로 만든다. 앞뒤로 글자와 서식이 모두 같은 부분을 남긴다.
 *
 * 가운데의 글자가 같고 서식만 다르면(굵게 켜기 등) 삭제하고 다시 넣지 않고 retain(n, 바뀐 서식)으로 만든다. 그래야 그 사이 다른
 * 사람이 그 범위에 친 글자가 지워지지 않는다. 글자가 다르면 가운데를 지우고 새 글을 서식과 함께 넣는다.
 */
export function diffRich(before: RichText, after: RichText): Delta {
  const a = before.plainText()
  const b = after.plainText()
  const aAttrs = attrsPerUnit(before)
  const bAttrs = attrsPerUnit(after)
  const same = (i: number, j: number) =>
    a.charCodeAt(i) === b.charCodeAt(j) && A.equals(aAttrs[i] as A.Attrs, bAttrs[j] as A.Attrs)
  const limit = Math.min(a.length, b.length)
  let prefix = 0
  while (prefix < limit && same(prefix, prefix)) {
    prefix++
  }
  if (prefix > 0 && isHighSurrogate(a.charCodeAt(prefix - 1))) {
    prefix--
  }
  let suffix = 0
  while (suffix < limit - prefix && same(a.length - 1 - suffix, b.length - 1 - suffix)) {
    suffix++
  }
  if (suffix > 0 && isLowSurrogate(a.charCodeAt(a.length - suffix))) {
    suffix--
  }
  const builder = Delta.builder().retain(prefix)
  const oldMiddle = a.slice(prefix, a.length - suffix)
  const newMiddle = b.slice(prefix, b.length - suffix)
  if (oldMiddle === newMiddle) {
    for (let i = prefix; i < a.length - suffix; i++) {
      builder.retain(1, attrChange(aAttrs[i] as A.Attrs, bAttrs[i] as A.Attrs))
    }
    return builder.build()
  }
  builder.delete(oldMiddle.length)
  for (const segment of after.slice(prefix, b.length - suffix).segments) {
    builder.insert(segment.text, segment.attrs)
  }
  return builder.build()
}
