package com.copage.workspace.app;

/** 워크스페이스와 페이지 요청을 처리하지 못했다. 종류는 HTTP 상태로 바뀐다. */
public final class WorkspaceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 없거나, 속하지 않아 보이지 않는다. 둘을 구분하지 않아 존재 여부를 흘리지 않는다. */
        NOT_FOUND,
        /** 속해 있지만 역할이 모자란다. */
        FORBIDDEN,
        /** 이미 있다. */
        CONFLICT,
        /** 요청이 맞지 않다. */
        BAD_REQUEST
    }

    private final Kind kind;

    public WorkspaceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
