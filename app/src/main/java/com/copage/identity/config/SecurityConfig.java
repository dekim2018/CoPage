package com.copage.identity.config;

import com.copage.identity.app.AuthService;
import com.copage.identity.in.OAuthLoginHandlers;
import com.copage.identity.in.OAuthUserService;
import com.copage.identity.out.RedisAuthorizationRequests;
import com.copage.web.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 필터 체인 (docs/07-SECURITY.md 9.2). 경로에 따라 셋으로 나눈다.
 *
 * <ol>
 *   <li>{@code /api/**}: Access 토큰(JWT)으로 인증한다. {@code /api/auth/**} 만 열어 둔다
 *   <li>{@code /ws/**}: 핸드셰이크에서 1회용 티켓으로 인증하므로 여기서는 통과시킨다 (9.3)
 *   <li>그 밖: 오류 응답만 열고 모두 막는다
 * </ol>
 *
 * <p>관리 포트(지표, 상태 확인)는 따로 둔다. 관리 포트로 들어온 요청만 열고, 그 포트는 기본으로 이 기기에서만 받는다
 * (application.yml 의 management). API 포트의 /actuator 는 위 셋째 체인이 막는다.
 *
 * <p>JWT 검증은 Spring Security 의 리소스 서버(HS256)를 쓴다. 서명과 만료에 더해, 로그아웃으로 막은 토큰과 모든 세션을 끊은
 * 사용자의 예전 토큰을 거부하는 검증기를 붙였다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, AuthService authService, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        // 발급자와 대상이 이 서비스인 토큰만 받는다 (진단 SEC-5). 같은 비밀키를 다른 서비스와 나눠 쓰게 되어도 섞이지 않는다.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                new JwtIssuerValidator(properties.issuer()), audience, notRevoked(authService))));
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

    /**
     * 관리 포트로 들어온 요청. 포트 번호는 서버가 뜬 뒤 local.management.port 로 알 수 있어 요청마다 읽는다(0 이면 무작위 포트).
     * 관리 포트를 따로 두지 않으면(같은 포트) 이 체인은 아무 요청에도 맞지 않고, /actuator 는 셋째 체인이 막는다.
     */
    @Bean
    @Order(0)
    SecurityFilterChain management(HttpSecurity http, Environment environment) throws Exception {
        RequestMatcher onManagementPort = request -> {
            Integer managementPort = environment.getProperty("local.management.port", Integer.class);
            Integer serverPort = environment.getProperty("local.server.port", Integer.class);
            return managementPort != null && !managementPort.equals(serverPort) && request.getLocalPort() == managementPort;
        };
        return http.securityMatcher(onManagementPort)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
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
                        // 공유 링크(D-41). 토큰이 있으면 로그인한 사용자로 본다.
                        .requestMatchers("/api/share/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    /**
     * 구글, 깃허브 로그인 (A-4, D-53). /oauth2/authorization/{서비스} 로 보내고 /login/oauth2/code/{서비스} 로 돌아온다. 서버 세션을
     * 쓰지 않는다: 도중의 요청은 Redis 에, 로그인 결과는 Refresh 쿠키로 남긴다. 받은 서비스의 토큰은 쓰지 않아 들고 있지 않는다.
     * 켜진 서비스가 없으면 이 경로는 막힌다.
     */
    @Bean
    @Order(2)
    SecurityFilterChain oauthLogin(
            HttpSecurity http,
            OAuthProviders providers,
            AuthService authService,
            AuthProperties properties,
            OAuthProperties oauth,
            StringRedisTemplate redis,
            JsonMapper jsonMapper) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()));
        if (providers.repository() == null) {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
        }
        OAuthLoginHandlers handlers = new OAuthLoginHandlers(authService, properties);
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(login -> login
                        .clientRegistrationRepository(providers.repository())
                        .authorizedClientRepository(NO_AUTHORIZED_CLIENTS)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(new RedisAuthorizationRequests(redis, jsonMapper)))
                        .userInfoEndpoint(userInfo -> userInfo.userService(new OAuthUserService(oauth.githubEmailsUrl())))
                        .successHandler(handlers)
                        .failureHandler(handlers))
                .build();
    }

    /** 다른 서비스의 토큰은 로그인할 때 한 번 쓰고 들고 있지 않는다. */
    private static final OAuth2AuthorizedClientRepository NO_AUTHORIZED_CLIENTS = new OAuth2AuthorizedClientRepository() {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String clientRegistrationId, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }
    };

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
