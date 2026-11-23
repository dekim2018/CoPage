package com.copage.support;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트가 함께 쓰는 PostgreSQL 컨테이너. 테스트 실행 동안 한 번만 띄우고, 끝나면 Testcontainers 가 정리한다.
 *
 * <p>스프링 테스트는 이 클래스를 상속하면 데이터소스가 이 컨테이너를 가리킨다. 문서마다 무작위 UUID 를 쓰므로 테스트끼리
 * 데이터가 섞이지 않는다.
 */
public abstract class TestDatabase {

    public static final PostgreSQLContainer POSTGRES = start();

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .load()
                .migrate();
        return container;
    }

    /** 스프링 컨텍스트에 넘길 데이터소스 설정. */
    public static Map<String, Object> properties() {
        return Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword());
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        properties().forEach((key, value) -> registry.add(key, () -> value));
    }
}
