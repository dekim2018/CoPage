package com.copage.permission.in;

import com.copage.permission.app.PermissionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 권한 관리 오류를 RFC 9457 형식(ProblemDetail)으로 바꾼다. */
@RestControllerAdvice(basePackageClasses = PermissionExceptionHandler.class)
class PermissionExceptionHandler {

    @ExceptionHandler
    ResponseEntity<ProblemDetail> permission(PermissionException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, e.getMessage()));
    }
}
