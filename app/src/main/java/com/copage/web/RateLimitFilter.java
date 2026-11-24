package com.copage.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공개 배포에서 로그인 없이 부를 수 있는 경로의 빈도를 IP 마다 제한한다 (로드맵 7단계, D-28 의 남은 일, D-48). 가입으로 이메일이
 * 있는지 대량으로 알아보거나, 공유 링크의 토큰을 마구 넣어 보거나, 여러 이메일로 로그인을 시도하는 것을 늦춘다. 같은 이메일로
 * 틀리는 것은 따로 잠근다(로그인 실패 횟수).
 *
 * <p>창은 고정 창이다(창마다 Redis 키 하나). 창 경계에서 잠깐 두 배까지 받을 수 있다. 정밀한 제한이 아니라 대량 시도를 늦추는
 * 것이 목적이다. IP 는 프록시 뒤면 Tomcat 이 X-Forwarded-For 로 바꾼 값이다(내부 주소의 프록시만 믿는다, D-28). Redis 에 닿지
 * 못하면 예외가 그대로 나가 503 이 된다(fail-closed, StoreUnavailableFilter).
 */
final class RateLimitFilter extends OncePerRequestFilter {

    /** @param path 경로 정규식 */
    record Rule(String name, String method, Pattern path, int limit, Duration window) {
    }

    /** INCR 하고 처음이면 만료를 건다. 둘을 한 번에 해 만료 없는 키가 남지 않게 한다. */
    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final List<Rule> rules;

    RateLimitFilter(StringRedisTemplate redis, JsonMapper mapper, List<Rule> rules) {
        this.redis = redis;
        this.mapper = mapper;
        this.rules = rules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        for (Rule rule : rules) {
            if (rule.method().equals(request.getMethod()) && rule.path().matcher(path).matches()) {
                long window = rule.window().toMillis();
                long slot = System.currentTimeMillis() / window;
                String key = "copage:rate:" + rule.name() + ":" + request.getRemoteAddr() + ":" + slot;
                Long count = redis.execute(HIT, List.of(key), String.valueOf(window));
                if (count != null && count > rule.limit()) {
                    long retryAfter = Math.max(1, ((slot + 1) * window - System.currentTimeMillis() + 999) / 1000);
                    reject(response, retryAfter);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦다. 잠시 뒤에 다시 시도한다");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
