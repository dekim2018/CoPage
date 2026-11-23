/**
 * 글자 서식(굵게, 링크, 댓글 표시 등)과 블록 속성을 다루는 도우미. Java 의 `com.copage.ot.text.Attrs` 와 같은 규칙이다.
 *
 * 서식은 이름과 값의 객체다. 연산의 retain 조각에서는 값이 null 이면 "이 서식을 지운다"는 뜻이고, 문서에 저장된 글자의
 * 서식에는 null 값이 들어가지 않는다. 키는 항상 이름 순(UTF-16 코드 단위 순)으로 정렬해 둔다.
 */

export type AttrValue = string | number | boolean | null | readonly AttrValue[] | { readonly [key: string]: AttrValue }

export type Attrs = { readonly [key: string]: AttrValue }

export const NO_ATTRS: Attrs = Object.freeze({})

/** 문자열을 UTF-16 코드 단위 순으로 비교한다. Java 의 `String.compareTo` 와 같은 순서다. */
export function compareStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

export function isEmpty(attrs: Attrs): boolean {
  return Object.keys(attrs).length === 0
}

export function has(attrs: Attrs, key: string): boolean {
  return Object.hasOwn(attrs, key)
}

/** 키 순서를 고정한 읽기 전용 사본을 만든다. null 값을 허용한다. */
export function copy(attrs: Attrs | null | undefined): Attrs {
  if (attrs == null) {
    return NO_ATTRS
  }
  const keys = Object.keys(attrs)
  if (keys.length === 0) {
    return NO_ATTRS
  }
  keys.sort(compareStrings)
  // fromEntries 는 "__proto__" 같은 키도 그냥 속성으로 만든다.
  return Object.freeze(Object.fromEntries(keys.map((key) => [key, attrs[key] as AttrValue])))
}

export function of(key: string, value: AttrValue): Attrs {
  return copy(Object.fromEntries([[key, value]]))
}

/** base 위에 change 를 덮어쓴다. keepNull 이 false 면 null 값(삭제 표시)을 결과에서 뺀다. */
export function merge(base: Attrs, change: Attrs, keepNull: boolean): Attrs {
  const merged = new Map<string, AttrValue>(Object.entries(base))
  for (const [key, value] of Object.entries(change)) {
    merged.set(key, value)
  }
  if (!keepNull) {
    for (const [key, value] of merged) {
      if (value === null) {
        merged.delete(key)
      }
    }
  }
  return copy(Object.fromEntries(merged))
}

/** attrs 에서 other 가 건드리는 키를 뺀다. 먼저 확정된 쪽이 나중 쪽에 양보할 때 쓴다. */
export function without(attrs: Attrs, other: Attrs): Attrs {
  if (isEmpty(attrs) || isEmpty(other)) {
    return attrs
  }
  return copy(Object.fromEntries(Object.entries(attrs).filter(([key]) => !has(other, key))))
}

/** change 를 적용하기 전의 값을 current 에서 뽑는다. 없던 서식은 null(지우기)로 적는다. */
export function previousValues(current: Attrs, change: Attrs): Attrs {
  return copy(Object.fromEntries(Object.keys(change).map((key) => [key, has(current, key) ? current[key] as AttrValue : null])))
}

/** 키 순서와 상관없이 같은 서식인지. 값은 깊이 비교한다. */
export function equals(a: Attrs, b: Attrs): boolean {
  return valueEquals(a, b)
}

export function valueEquals(a: AttrValue | undefined, b: AttrValue | undefined): boolean {
  if (a === b) {
    return true
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return false
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) {
      return false
    }
    return a.every((item, i) => valueEquals(item, b[i]))
  }
  const left = a as Attrs
  const right = b as Attrs
  const keys = Object.keys(left)
  if (keys.length !== Object.keys(right).length) {
    return false
  }
  return keys.every((key) => has(right, key) && valueEquals(left[key], right[key]))
}
