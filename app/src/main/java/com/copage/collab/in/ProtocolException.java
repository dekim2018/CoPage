package com.copage.collab.in;

/** 받은 메시지가 규약에 맞지 않는다. 연결을 4008 로 끊는다. */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
