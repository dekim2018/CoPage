package com.copage.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import tools.jackson.databind.json.JsonMapper;

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
    void 트랜잭션을_열_연결을_얻지_못해도_503_으로_본다() {
        // DataSourceTransactionManager 는 풀의 SQLException 을 그대로 원인으로 감싼다. DataAccessException 이 아니다.
        assertTrue(StoreUnavailableFilter.unavailable(new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("Connection is not available, request timed out after 3000ms"))));
        assertTrue(StoreUnavailableFilter.unavailable(new SQLTransientConnectionException("풀이 비었다")));
    }

    @Test
    void 트랜잭션_설정_오류는_503_으로_바꾸지_않는다() {
        assertFalse(StoreUnavailableFilter.unavailable(new NestedTransactionNotSupportedException("중첩 트랜잭션")));
    }

    @Test
    void 같은_종류의_장애는_10초에_한_번만_WARNING_으로_남긴다() {
        long[] now = {0};
        StoreUnavailableFilter filter = new StoreUnavailableFilter(JsonMapper.builder().build(), () -> now[0]);

        assertTrue(filter.shouldWarn("QueryTimeoutException"));
        assertFalse(filter.shouldWarn("QueryTimeoutException"));
        assertTrue(filter.shouldWarn("RedisConnectionException"), "다른 종류는 따로 센다");
        now[0] = Duration.ofSeconds(9).toNanos();
        assertFalse(filter.shouldWarn("QueryTimeoutException"));
        now[0] = Duration.ofSeconds(10).toNanos();
        assertTrue(filter.shouldWarn("QueryTimeoutException"));
    }

    @Test
    void 그_밖의_오류는_503_으로_바꾸지_않는다() {
        assertFalse(StoreUnavailableFilter.unavailable(new IllegalStateException("버그")));
        assertFalse(StoreUnavailableFilter.unavailable(new DataIntegrityViolationException("제약 위반")));
        assertFalse(StoreUnavailableFilter.unavailable(new ServletException("요청 처리 실패", new NullPointerException())));
    }
}
