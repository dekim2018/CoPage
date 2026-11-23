package com.copage.identity.config;

import com.copage.identity.app.AuthService;
import com.copage.identity.out.JdbcUserRepository;
import com.copage.identity.out.RedisAccessTokenRevocations;
import com.copage.identity.out.RedisLoginAttempts;
import com.copage.identity.out.RedisRefreshTokenStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 계정 모듈의 빈. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class IdentityConfig {

    private final AuthProperties properties;

    IdentityConfig(AuthProperties properties) {
        this.properties = properties;
    }

    /** Access 토큰 서명 키. 짧거나 없으면 서버를 띄우지 않는다. */
    @Bean
    SecretKey jwtKey() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "copage.auth.jwt-secret(환경 변수 COPAGE_JWT_SECRET)에 32 바이트 이상의 키를 넣어야 한다");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return NimbusJwtEncoder.withSecretKey(jwtKey).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthService authService(
            JdbcClient jdbcClient,
            StringRedisTemplate redis,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${spring.data.redis.timeout:2s}") Duration redisTimeout) {
        return new AuthService(
                new JdbcUserRepository(jdbcClient),
                new RedisRefreshTokenStore(redis, properties.refreshReuseGrace(), redisTimeout, Clock.systemUTC()),
                new RedisAccessTokenRevocations(redis),
                new RedisLoginAttempts(
                        redis, properties.loginMaxFailures(), properties.loginWindow(), properties.loginLock()),
                passwordEncoder,
                jwtEncoder,
                new AuthService.TokenSettings(properties.accessTokenTtl(), properties.refreshTokenTtl()),
                Clock.systemUTC());
    }
}
