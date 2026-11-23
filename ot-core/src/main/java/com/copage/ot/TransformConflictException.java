package com.copage.ot;

/**
 * 두 연산을 변환으로 풀 수 없을 때 던진다. 나중에 도착한 묶음이 거부된다.
 *
 * <p>예: 다른 블록에 합쳐진 블록 아래로 누군가 동시에 블록을 옮긴 경우. 이런 충돌까지 변환으로 풀면 규칙이 크게 늘어서,
 * 드문 경우는 거부하고 빈도를 지표로 본다 (docs/adr/0012).
 */
public class TransformConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransformConflictException(String message) {
        super(message);
    }
}
