import { Delta } from '../ot/delta'

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
