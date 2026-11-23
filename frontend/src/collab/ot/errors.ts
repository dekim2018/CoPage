/** 잘못된 인자. Java 의 IllegalArgumentException 에 해당한다. 텍스트 편집의 길이가 맞지 않을 때도 쓴다. */
export class InvalidArgumentError extends Error {
  override name = 'InvalidArgumentError'
}

/** 연산을 지금 문서에 적용할 수 없을 때 던진다. 서버는 이 경우 묶음 전체를 거부(nack)한다. */
export class InvalidOperationError extends Error {
  override name = 'InvalidOperationError'
}

/**
 * 두 연산을 변환으로 풀 수 없을 때 던진다. 나중에 도착한 묶음이 거부된다.
 *
 * 예: 다른 블록에 합쳐진 블록 아래로 누군가 동시에 블록을 옮긴 경우 (docs/adr/0012).
 */
export class TransformConflictError extends Error {
  override name = 'TransformConflictError'
}
