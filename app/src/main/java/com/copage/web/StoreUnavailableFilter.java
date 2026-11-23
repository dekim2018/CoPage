package com.copage.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * <p>연결이 끊긴 경우({@link DataAccessResourceFailureException})와 답이 없는 경우({@link QueryTimeoutException})만 503 으로
 * 바꾼다. 그 밖의 데이터 오류는 서버 버그일 수 있어 지금처럼 500 으로 둔다.
 */
final class StoreUnavailableFilter extends OncePerRequestFilter {

    private static final System.Logger LOG = System.getLogger(StoreUnavailableFilter.class.getName());
    static final String RETRY_AFTER_SECONDS = "5";

    private final JsonMapper mapper;

    StoreUnavailableFilter(JsonMapper mapper) {
        this.mapper = mapper;
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
            LOG.log(System.Logger.Level.WARNING, request.getMethod() + " " + request.getRequestURI()
                    + ": 저장소에 닿지 못해 503 으로 답한다: " + rootMessage(e));
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
            if (cause instanceof DataAccessResourceFailureException || cause instanceof QueryTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
