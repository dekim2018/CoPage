package com.copage.identity.config;

import com.copage.identity.app.AuthService;
import com.copage.identity.app.UserDirectory;
import com.copage.identity.out.JdbcUserDirectory;
import com.copage.identity.out.JdbcUserRepository;
import com.copage.identity.out.RedisAccessTokenRevocations;
import com.copage.identity.out.RedisLoginAttempts;
import com.copage.identity.out.RedisRefreshTokenStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 계정 모듈의 빈. */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, OAuthProperties.class})
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
    UserDirectory userDirectory(JdbcClient jdbcClient) {
        return new JdbcUserDirectory(jdbcClient);
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
                new AuthService.TokenSettings(
                        properties.accessTokenTtl(), properties.refreshTokenTtl(), properties.issuer(), properties.audience()),
                Clock.systemUTC());
    }

    /**
     * 다른 서비스 로그인 (A-4, D-54). 스프링의 표준 설정(spring.security.oauth2.client.registration.*)으로 만든 것이 있으면 그것을
     * 쓰고(시험의 가짜 서비스), 없으면 COPAGE_GOOGLE_*, COPAGE_GITHUB_* 로 만든다. 구글은 OIDC 로 확인된 이메일을 받고, 깃허브는
     * 이메일 목록을 읽을 수 있게 user:email 을 청한다.
     */
    @Bean
    OAuthProviders oauthProviders(OAuthProperties oauth, ObjectProvider<ClientRegistrationRepository> configured) {
        ClientRegistrationRepository existing = configured.getIfAvailable();
        if (existing instanceof Iterable<?> registrations) {
            List<String> ids = new ArrayList<>();
            registrations.forEach(registration -> ids.add(((ClientRegistration) registration).getRegistrationId()));
            return new OAuthProviders(existing, List.copyOf(ids));
        }
        List<ClientRegistration> registrations = new ArrayList<>();
        if (oauth.google()) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(oauth.googleClientId())
                    .clientSecret(oauth.googleClientSecret())
                    .scope("openid", "email", "profile")
                    .build());
        }
        if (oauth.github()) {
            registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(oauth.githubClientId())
                    .clientSecret(oauth.githubClientSecret())
                    .scope("read:user", "user:email")
                    .build());
        }
        if (registrations.isEmpty()) {
            return new OAuthProviders(null, List.of());
        }
        return new OAuthProviders(new InMemoryClientRegistrationRepository(registrations),
                registrations.stream().map(ClientRegistration::getRegistrationId).toList());
    }
}
