package com.copage.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.authentication.AuthenticationServiceException;

/** 어떤 예외를 503 으로 바꾸는지. 503 응답 자체는 RedisOutageTest 가 실제 서버로 확인한다. */
class StoreUnavailableFilterTest {

    @Test
    void 연결_실패와_응답_시간_초과는_감싸여_있어도_503_으로_본다() {
        assertTrue(StoreUnavailableFilter.unavailable(new RedisConnectionFailureException("끊김")));
        assertTrue(StoreUnavailableFilter.unavailable(new QueryTimeoutException("시간 초과")));
        assertTrue(StoreUnavailableFilter.unavailable(new CannotGetJdbcConnectionException("DB 끊김")));
        assertTrue(StoreUnavailableFilter.unavailable(
                new ServletException("요청 처리 실패", new QueryTimeoutException("시간 초과"))));
        assertTrue(StoreUnavailableFilter.unavailable(
                new AuthenticationServiceException("검증 실패", new RedisConnectionFailureException("끊김"))));
    }

    @Test
    void 그_밖의_오류는_503_으로_바꾸지_않는다() {
        assertFalse(StoreUnavailableFilter.unavailable(new IllegalStateException("버그")));
        assertFalse(StoreUnavailableFilter.unavailable(new DataIntegrityViolationException("제약 위반")));
        assertFalse(StoreUnavailableFilter.unavailable(new ServletException("요청 처리 실패", new NullPointerException())));
    }
}
