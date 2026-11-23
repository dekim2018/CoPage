package com.copage.collab.domain;

/** 연산이나 문서의 JSON 이 형식에 맞지 않는다. 클라이언트가 보낸 메시지라면 규약 위반이다. */
public class JsonFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonFormatException(String message) {
        super(message);
    }

    public JsonFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
