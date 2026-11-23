package com.copage.identity.app;

/** 로그인 실패 횟수 포트 (docs/07-SECURITY.md 9.1 의 로그인 보호). 몇 번에 얼마나 잠글지는 구현이 정한다. */
public interface LoginAttempts {

    boolean isLocked(String email);

    void recordFailure(String email);

    void reset(String email);
}
