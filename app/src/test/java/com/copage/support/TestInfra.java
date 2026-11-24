package com.copage.support;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트가 함께 쓰는 PostgreSQL 과 Redis 컨테이너. 테스트 실행 동안 한 번만 띄우고, 끝나면 Testcontainers 가 정리한다.
 *
 * <p>스프링 테스트는 이 클래스를 상속하면 데이터소스와 Redis 가 이 컨테이너를 가리킨다. 문서와 사용자마다 무작위 값을 쓰므로
 * 테스트끼리 데이터가 섞이지 않는다.
 */
public abstract class TestInfra {

    public static final PostgreSQLContainer POSTGRES = startPostgres();
    public static final GenericContainer<?> REDIS = startRedis();

    /** 테스트에서만 쓰는 JWT 서명 키. */
    public static final String JWT_SECRET = "test-only-secret-0123456789abcdefghijklmnopqrstuvwxyz";

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .load()
                .migrate();
        return container;
    }

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7").withExposedPorts(6379);
        container.start();
        return container;
    }

    /** 스프링 컨텍스트에 넘길 설정. */
    public static Map<String, Object> properties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.data.redis.host", REDIS.getHost());
        properties.put("spring.data.redis.port", REDIS.getMappedPort(6379));
        properties.put("copage.auth.jwt-secret", JWT_SECRET);
        // 시험은 한 주소에서 사용자를 많이 만든다. 빈도 제한은 RateLimitTest 가 따로 켜서 본다.
        properties.put("copage.web.rate-limit", false);
        return properties;
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        properties().forEach((key, value) -> registry.add(key, () -> value));
    }
}
