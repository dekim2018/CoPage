package com.copage.identity.app;

/** 인증 처리가 실패했다. 입구(in)가 kind 를 HTTP 상태로 바꾼다. */
public class AuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 이메일이 이미 있다. */
        DUPLICATE_EMAIL,
        /** 이메일이나 비밀번호가 틀렸다. 어느 쪽이 틀렸는지는 알려 주지 않는다. */
        BAD_CREDENTIALS,
        /** 로그인 실패가 많아 잠겼다. */
        LOCKED,
        /** Refresh 토큰이 없거나 만료되었거나 이미 쓰였다. */
        INVALID_REFRESH_TOKEN,
        /** 같은 Refresh 토큰으로 다른 요청이 방금 재발급했다. 쿠키에 새 토큰이 있으니 다시 시도한다. */
        REFRESH_RACE,
        /** 사용자가 없다. */
        NOT_FOUND
    }

    private final Kind kind;

    public AuthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
