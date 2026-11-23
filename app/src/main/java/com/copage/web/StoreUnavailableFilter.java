package com.copage.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장소(Redis, PostgreSQL)에 닿지 못해 요청을 처리하지 못하면 503 과 Retry-After 로 답한다 (docs/07-SECURITY.md 9.7).
 *
 * <p>Redis 장애 정책은 막힘(fail-closed)이다. Access 토큰의 차단 확인, 재발급, 로그인, 티켓 발급과 사용은 Redis 없이 판단하지
 * 않는다. 그런데 이 예외는 Spring Security 필터 안(토큰 검증기)에서도 나므로 컨트롤러 조언으로는 잡을 수 없고, 그대로 두면 500
 * 이 된다. 그래서 보안 필터보다 바깥에서 잡는다. 401 이 아니라 503 이라 클라이언트는 로그아웃하지 않고 다시 시도한다.
 *
 * <p>저장소에 닿지 못한 경우만 503 으로 바꾼다. 그 밖의 데이터 오류는 서버 버그일 수 있어 지금처럼 500 으로 둔다.
 *
 * <ul>
 *   <li>{@link DataAccessResourceFailureException}: Redis 연결 실패, 트랜잭션 밖의 JDBC 연결 실패
 *   <li>{@link QueryTimeoutException}: Redis 명령 시간 초과
 *   <li>{@link SQLTransientConnectionException}: 연결 풀이 시간 안에 연결을 내주지 못함. 트랜잭션을 열다 실패하면
 *       {@code CannotCreateTransactionException}(DataAccessException 이 아니다)의 원인으로 온다. 그 예외 자체는 보지 않는다.
 *       {@code NestedTransactionNotSupportedException} 같은 프로그램 오류도 그 하위 타입이라 503 으로 바꾸면 안 된다
 * </ul>
 *
 * <p>장애 동안에는 모든 요청이 여기로 온다. 로그가 원인을 가리지 않도록 예외 종류마다 10초에 한 번만 WARNING 으로 남기고 나머지는
 * DEBUG 로 남긴다.
 */
final class StoreUnavailableFilter extends OncePerRequestFilter {

    private static final System.Logger LOG = System.getLogger(StoreUnavailableFilter.class.getName());
    static final String RETRY_AFTER_SECONDS = "5";

    private static final long WARN_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final JsonMapper mapper;
    private final LongSupplier nanoTime;
    private final Map<String, Long> lastWarned = new ConcurrentHashMap<>();

    StoreUnavailableFilter(JsonMapper mapper) {
        this(mapper, System::nanoTime);
    }

    StoreUnavailableFilter(JsonMapper mapper, LongSupplier nanoTime) {
        this.mapper = mapper;
        this.nanoTime = nanoTime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            if (!unavailable(e) || response.isCommitted()) {
                throw e;
            }
            String root = rootMessage(e);
            LOG.log(shouldWarn(root.substring(0, root.indexOf(':'))) ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG,
                    request.getMethod() + " " + request.getRequestURI() + ": 저장소에 닿지 못해 503 으로 답한다: " + root);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "잠시 서비스를 쓸 수 없다. 잠시 뒤 다시 시도한다");
            problem.setInstance(java.net.URI.create(request.getRequestURI()));
            response.resetBuffer();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(mapper.writeValueAsString(problem));
        }
    }

    /** 원인 사슬 어딘가에 저장소 연결 실패나 응답 시간 초과가 있는지. 필터와 서블릿이 예외를 감쌀 수 있다. */
    static boolean unavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof SQLTransientConnectionException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** 이 종류의 예외를 마지막으로 WARNING 으로 남긴 지 10초가 지났으면 true 이고 지금을 기록한다. */
    boolean shouldWarn(String kind) {
        long now = nanoTime.getAsLong();
        boolean[] warn = {false};
        lastWarned.compute(kind, (key, last) -> {
            if (last == null || now - last >= WARN_INTERVAL_NANOS) {
                warn[0] = true;
                return now;
            }
            return last;
        });
        return warn[0];
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
