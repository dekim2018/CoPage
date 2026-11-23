package com.copage.identity.in;

import com.copage.identity.app.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 인증 오류를 RFC 9457 형식(ProblemDetail)으로 바꾼다 (docs/09-API.md). */
@RestControllerAdvice(basePackageClasses = AuthExceptionHandler.class)
class AuthExceptionHandler {

    static ProblemDetail problem(AuthException e) {
        HttpStatus status = switch (e.kind()) {
            case DUPLICATE_EMAIL -> HttpStatus.CONFLICT;
            case BAD_CREDENTIALS, INVALID_REFRESH_TOKEN -> HttpStatus.UNAUTHORIZED;
            case LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> auth(AuthException e) {
        ProblemDetail problem = problem(e);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }
}
