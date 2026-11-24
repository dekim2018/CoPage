import { InvalidArgumentError } from './errors'

/*
 * 형제 블록 사이 순서를 정하는 키를 만든다. Java 의 `com.copage.ot.FractionalIndex` 와 같은 규칙이다.
 *
 * 두 키 사이에는 항상 새 키를 만들 수 있다. 그래서 블록을 끼워 넣거나 옮길 때 다른 블록의 키를 고치지 않는다. 키는 문자열
 * 순서(UTF-16 코드 단위 순)로 비교한다.
 *
 * 맨 뒤에 붙이기와 맨 앞에 넣기는 키가 길어지지 않게 따로 다룬다(DC-5, D-24). 첫 글자가 같은 키들 안에서 정해진 길이(1~4)로
 * 하나씩 세고, 다 쓰면 첫 글자를 하나 옮긴다. 'z'(맨 앞에 넣기는 '0')로 시작하는 키는 그 글자를 떼고 나머지에서 한다.
 */

const DIGITS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
const ZERO = '0'
const LAST = 'z'
const MIDDLE = DIGITS.length / 2
const MAX_LEVEL = 4

/**
 * before 와 after 사이에 놓이는 키를 만든다.
 *
 * @param before 앞 형제의 키. 맨 앞에 넣을 때는 null
 * @param after 뒤 형제의 키. 맨 뒤에 넣을 때는 null
 */
export function between(before: string | null, after: string | null): string {
  const low = before ?? ''
  if (after !== null && low >= after) {
    throw new InvalidArgumentError(`앞 키가 뒤 키보다 작아야 한다: ${before}, ${after}`)
  }
  validate(low)
  if (after !== null) {
    validate(after)
  }
  if (after === null) {
    return low.length === 0 ? DIGITS.charAt(MIDDLE) : append(low)
  }
  if (low.length === 0) {
    return prepend(after)
  }
  return midpoint(low, after)
}

function append(low: string): string {
  const first = DIGITS.indexOf(low.charAt(0))
  const rest = low.substring(1)
  if (first === DIGITS.length - 1) {
    return LAST + (rest.length === 0 ? DIGITS.charAt(MIDDLE) : append(rest))
  }
  const suffix = increaseTo(rest, appendLevel(first))
  if (suffix !== null) {
    return low.charAt(0) + suffix
  }
  const next = first + 1
  if (next === DIGITS.length - 1) {
    return LAST + DIGITS.charAt(MIDDLE)
  }
  return DIGITS.charAt(next) + ZERO.repeat(appendLevel(next) - 1) + DIGITS.charAt(1)
}

function prepend(high: string): string {
  const first = DIGITS.indexOf(high.charAt(0))
  const rest = high.substring(1)
  if (first === 0) {
    return ZERO + prepend(rest)
  }
  const suffix = decreaseTo(rest, prependLevel(first))
  if (suffix !== null) {
    return high.charAt(0) + suffix
  }
  const next = first - 1
  if (next === 0) {
    return ZERO + DIGITS.charAt(MIDDLE)
  }
  return DIGITS.charAt(next) + LAST.repeat(prependLevel(next))
}

function appendLevel(first: number): number {
  return Math.min(MAX_LEVEL, Math.max(1, first - MIDDLE + 1))
}

function prependLevel(first: number): number {
  return Math.min(MAX_LEVEL, Math.max(1, MIDDLE - first))
}

function increaseTo(rest: string, length: number): string | null {
  let candidate =
    rest.length < length
      ? rest + ZERO.repeat(length - rest.length - 1) + DIGITS.charAt(1)
      : increment(rest.substring(0, length))
  if (candidate !== null && candidate.endsWith(ZERO)) {
    candidate = increment(candidate)
  }
  return candidate
}

function decreaseTo(rest: string, length: number): string | null {
  let candidate =
    rest.length > length ? rest.substring(0, length) : decrement(rest + ZERO.repeat(length - rest.length))
  if (candidate !== null && candidate.endsWith(ZERO)) {
    candidate = decrement(candidate)
  }
  return candidate
}

function increment(digits: string): string | null {
  const chars = digits.split('')
  for (let i = chars.length - 1; i >= 0; i--) {
    const digit = DIGITS.indexOf(chars[i] as string)
    if (digit < DIGITS.length - 1) {
      chars[i] = DIGITS.charAt(digit + 1)
      return chars.join('')
    }
    chars[i] = ZERO
  }
  return null
}

function decrement(digits: string): string | null {
  const chars = digits.split('')
  for (let i = chars.length - 1; i >= 0; i--) {
    const digit = DIGITS.indexOf(chars[i] as string)
    if (digit > 0) {
      chars[i] = DIGITS.charAt(digit - 1)
      const result = chars.join('')
      return /^0*$/.test(result) ? null : result
    }
    chars[i] = LAST
  }
  return null
}

function validate(key: string): void {
  for (let i = 0; i < key.length; i++) {
    if (DIGITS.indexOf(key.charAt(i)) < 0) {
      throw new InvalidArgumentError(`순서 키에 쓸 수 없는 글자가 있다: ${key}`)
    }
  }
  if (key.endsWith(ZERO)) {
    throw new InvalidArgumentError(`순서 키는 0 으로 끝날 수 없다: ${key}`)
  }
}

function midpoint(low: string, high: string | null): string {
  if (high !== null) {
    let common = 0
    while (common < high.length && digitAt(low, common) === high[common]) {
      common++
    }
    if (common > 0) {
      const lowRest = low.length > common ? low.substring(common) : ''
      return high.substring(0, common) + midpoint(lowRest, high.substring(common))
    }
  }
  const lowDigit = low.length === 0 ? 0 : DIGITS.indexOf(low.charAt(0))
  const highDigit = high === null ? DIGITS.length : DIGITS.indexOf(high.charAt(0))
  if (highDigit - lowDigit > 1) {
    return DIGITS.charAt(Math.floor((lowDigit + highDigit) / 2))
  }
  if (high !== null && high.length > 1) {
    return high.substring(0, 1)
  }
  const lowRest = low.length > 1 ? low.substring(1) : ''
  return DIGITS.charAt(lowDigit) + midpoint(lowRest, null)
}

function digitAt(key: string, index: number): string {
  return index < key.length ? (key[index] as string) : ZERO
}
