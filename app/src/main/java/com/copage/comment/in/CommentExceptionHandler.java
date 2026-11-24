package com.copage.comment.in;

import com.copage.comment.app.CommentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 댓글 오류를 RFC 9457 형식(ProblemDetail)으로 바꾼다 (docs/09-API.md). */
@RestControllerAdvice(basePackageClasses = CommentExceptionHandler.class)
class CommentExceptionHandler {

    @ExceptionHandler(CommentException.class)
    ResponseEntity<ProblemDetail> comment(CommentException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, e.getMessage()));
    }
}
