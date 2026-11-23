package com.copage.ot.model;

/** 연산을 지금 문서에 적용할 수 없을 때 던진다. 서버는 이 예외를 받으면 묶음 전체를 거부(nack)한다. */
public class InvalidOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidOperationException(String message) {
        super(message);
    }
}
