package com.copage.comment.app;

/** 댓글 요청을 거절한다. 입구가 상태 코드로 바꾼다. */
public final class CommentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        NOT_FOUND,
        FORBIDDEN,
        BAD_REQUEST
    }

    private final Kind kind;

    public CommentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
