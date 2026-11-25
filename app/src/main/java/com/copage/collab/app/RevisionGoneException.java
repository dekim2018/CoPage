package com.copage.collab.app;

/**
 * 연산 로그를 정리해 더는 열 수 없는 과거 시점 (D-52). 남겨 둔 버전의 시점과 로그를 지운 지점은 고정 스냅샷이 있어 열린다.
 */
public final class RevisionGoneException extends RuntimeException {

    public RevisionGoneException(long rev) {
        super("리비전 " + rev + " 은 연산 로그를 정리해 열 수 없다");
    }
}
