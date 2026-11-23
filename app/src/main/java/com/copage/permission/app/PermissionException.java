package com.copage.permission.app;

/** 권한 관리 요청을 처리하지 못했다. 종류는 HTTP 상태로 바뀐다. */
public final class PermissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 없거나 볼 수 없다. 둘을 구분하지 않는다 (D-10). */
        NOT_FOUND,
        /** 볼 수는 있지만 FULL 이 아니다. */
        FORBIDDEN,
        BAD_REQUEST
    }

    private final Kind kind;

    public PermissionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
