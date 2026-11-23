package com.copage.identity.config;

import com.copage.identity.app.AuthService;
import com.copage.web.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 필터 체인 (docs/07-SECURITY.md 9.2). 경로에 따라 셋으로 나눈다.
 *
 * <ol>
 *   <li>{@code /api/**}: Access 토큰(JWT)으로 인증한다. {@code /api/auth/**} 만 열어 둔다
 *   <li>{@code /ws/**}: 핸드셰이크에서 1회용 티켓으로 인증하므로 여기서는 통과시킨다 (9.3)
 *   <li>그 밖: 오류 응답만 열고 모두 막는다
 * </ol>
 *
 * <p>JWT 검증은 Spring Security 의 리소스 서버(HS256)를 쓴다. 서명과 만료에 더해, 로그아웃으로 막은 토큰과 모든 세션을 끊은
 * 사용자의 예전 토큰을 거부하는 검증기를 붙였다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, AuthService authService) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(notRevoked(authService))));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> notRevoked(AuthService authService) {
        return jwt -> {
            UUID userId;
            try {
                userId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                return invalid("사용자 ID 가 없다");
            }
            if (jwt.getId() == null) {
                return invalid("토큰 ID 가 없다");
            }
            if (authService.isRevoked(userId, jwt.getId(), jwt.getIssuedAt())) {
                return invalid("막힌 토큰이다");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult invalid(String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
    }

    /**
     * {@code /api/auth/**} 에서는 Authorization 헤더를 읽지 않는다. 만료된 토큰을 붙인 채 로그인이나 재발급을 해도 401 이 나지
     * 않게 하기 위해서다. 로그아웃은 컨트롤러가 헤더를 직접 읽는다.
     */
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) ->
                request.getRequestURI().startsWith("/api/auth/") ? null : resolver.resolve(request);
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, JwtDecoder jwtDecoder, WebProperties web) throws Exception {
        return http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .cors(c -> c.configurationSource(cors(web)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSocket(HttpSecurity http) throws Exception {
        return http.securityMatcher("/ws/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain rest(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * 개발 서버처럼 다른 출처의 화면이 API 를 부를 수 있게 한다. Refresh 쿠키 때문에 자격 증명을 허용한다. 빈으로 내놓지 않는다.
     * Spring MVC 의 HandlerMappingIntrospector 도 같은 타입이라 빈이 둘이 된다.
     */
    private static CorsConfigurationSource cors(WebProperties web) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(web.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
