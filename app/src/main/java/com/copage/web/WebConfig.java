package com.copage.web;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 모듈들이 함께 쓰는 웹 설정을 올린다. */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
class WebConfig {

    @Bean
    NodeIdentity nodeIdentity(WebProperties web) {
        String id = web.nodeId();
        return new NodeIdentity(id == null || id.isBlank() ? "node-" + UUID.randomUUID() : id);
    }

    /** 보안 필터 체인보다 먼저 둔다. 토큰 검증 중에 난 저장소 오류도 잡아야 한다. */
    @Bean
    FilterRegistrationBean<StoreUnavailableFilter> storeUnavailableFilter(JsonMapper jsonMapper) {
        FilterRegistrationBean<StoreUnavailableFilter> registration =
                new FilterRegistrationBean<>(new StoreUnavailableFilter(jsonMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 로그인 없이 부를 수 있는 경로의 IP 별 빈도 제한 (D-48). 저장소 오류를 503 으로 바꾸는 필터 안쪽, 보안 필터 바깥에 둔다.
     * 값은 사람이 쓰기에는 넉넉하고 스크립트로 훑기에는 느린 정도다.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            WebProperties web, StringRedisTemplate redis, JsonMapper jsonMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(new RateLimitFilter(redis, jsonMapper,
                List.of(
                        new RateLimitFilter.Rule("signup", "POST", Pattern.compile("/api/auth/signup"), 10, Duration.ofHours(1)),
                        new RateLimitFilter.Rule("login", "POST", Pattern.compile("/api/auth/login"), 30, Duration.ofMinutes(1)),
                        new RateLimitFilter.Rule("share", "GET", Pattern.compile("/api/share/[^/]+"), 60, Duration.ofMinutes(1)),
                        new RateLimitFilter.Rule("share-ticket", "POST", Pattern.compile("/api/share/[^/]+/ws-ticket"), 60,
                                Duration.ofMinutes(1)),
                        new RateLimitFilter.Rule("invite", "GET", Pattern.compile("/api/invites/[^/]+"), 60,
                                Duration.ofMinutes(1)))));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setEnabled(web.rateLimit());
        return registration;
    }
}
