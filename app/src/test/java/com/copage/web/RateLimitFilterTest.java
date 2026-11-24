package com.copage.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.support.TestInfra;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 로그인 없이 부르는 경로의 IP 별 빈도 제한 (D-48). 실제 Redis 로 센다. */
class RateLimitFilterTest {

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(TestInfra.REDIS.getHost(), TestInfra.REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        connections.destroy();
    }

    private static int call(RateLimitFilter filter, String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void 창_안에서_한도를_넘으면_429_와_Retry_After_를_주고_다른_IP_와_다른_경로는_따로_센다() throws Exception {
        String rule = "t" + UUID.randomUUID();
        RateLimitFilter filter = new RateLimitFilter(redis, JsonMapper.builder().build(), List.of(
                new RateLimitFilter.Rule(rule, "POST", Pattern.compile("/api/auth/signup"), 3, Duration.ofMinutes(10))));

        for (int i = 0; i < 3; i++) {
            assertEquals(200, call(filter, "POST", "/api/auth/signup", "10.0.0.1"));
        }
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signup");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(429, response.getStatus());
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) >= 1);
        assertTrue(response.getContentAsString().contains("요청이 너무 잦다"));

        assertEquals(200, call(filter, "POST", "/api/auth/signup", "10.0.0.2"), "다른 IP");
        assertEquals(200, call(filter, "GET", "/api/auth/signup", "10.0.0.1"), "다른 메서드");
        assertEquals(200, call(filter, "POST", "/api/auth/login", "10.0.0.1"), "규칙이 없는 경로");
        // 키에는 만료가 걸려 있다.
        String key = redis.keys("copage:rate:" + rule + ":10.0.0.1:*").iterator().next();
        assertTrue(redis.getExpire(key) > 0);
    }
}
