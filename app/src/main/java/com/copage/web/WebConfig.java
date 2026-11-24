package com.copage.web;

import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
}
