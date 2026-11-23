import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { isDeepStrictEqual } from 'node:util'

/**
 * ot-core 가 내보낸 적합성 벡터를 읽는다.
 *
 * 벡터는 저장소에 넣지 않고 `./gradlew :ot-core:exportVectors` 로 만든다. 위치는 기본으로 ot-core/build/vectors 이고,
 * 환경 변수 COPAGE_VECTORS_DIR 로 바꿀 수 있다.
 */
export const VECTORS_DIR = process.env.COPAGE_VECTORS_DIR ?? resolve(import.meta.dirname, '../../ot-core/build/vectors')

export function loadCases<T>(name: string): T[] {
  const file = resolve(VECTORS_DIR, name)
  if (!existsSync(file)) {
    throw new Error(`적합성 벡터 ${file} 이 없다. 저장소 루트에서 ./gradlew :ot-core:exportVectors 를 먼저 실행한다`)
  }
  const parsed = JSON.parse(readFileSync(file, 'utf8')) as { version: number; cases: T[] }
  if (parsed.version !== 1) {
    throw new Error(`${name} 의 형식 버전 ${parsed.version} 을 읽을 수 없다`)
  }
  return parsed.cases
}

/** 어긋난 사례. 시드가 있으면 Java 쪽에서 같은 사례를 다시 만들 수 있다. */
export interface Mismatch {
  where: string
  field: string
  expected: unknown
  actual: unknown
}

/** 어긋난 사례를 모은다. 너무 많이 쌓이지 않게 앞쪽 몇 건만 자세히 남기고 나머지는 개수만 센다. */
export class Mismatches {
  readonly samples: Mismatch[] = []
  count = 0

  constructor(private readonly keep = 5) {}

  /** expected 와 actual 이 다르면 기록하고 false 를 돌려준다. undefined 필드는 JSON 처럼 없는 것으로 본다. */
  check(where: string, field: string, expected: unknown, actual: unknown): boolean {
    const normalized = actual === undefined ? undefined : JSON.parse(JSON.stringify(actual))
    if (isDeepStrictEqual(expected, normalized)) {
      return true
    }
    this.add({ where, field, expected, actual: normalized })
    return false
  }

  add(mismatch: Mismatch): void {
    this.count++
    if (this.samples.length < this.keep) {
      this.samples.push(mismatch)
    }
  }
}

/**
 * 예외를 벡터의 오류 표시로 바꾼다. 예상한 종류가 아닌 예외는 unexpected 로 적어서, 테스트를 멈추지 않고 그 사례만 어긋난
 * 것으로 남긴다.
 */
export function orError<T>(
  compute: () => T,
  errorType: new (...args: never[]) => Error,
  kind: string,
): T | { error: string } | { unexpected: string } {
  try {
    return compute()
  } catch (e) {
    if (e instanceof errorType) {
      return { error: kind }
    }
    return { unexpected: String(e) }
  }
}
