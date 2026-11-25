package com.copage.identity.in;

import com.copage.identity.app.AuthException;
import com.copage.identity.app.AuthService;
import com.copage.identity.config.AuthProperties;
import com.copage.identity.config.OAuthProviders;
import com.copage.identity.domain.User;
import com.copage.web.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입, 로그인, 토큰 재발급, 로그아웃 (docs/09-API.md 11.1).
 *
 * <p>Access 토큰은 응답 본문으로, Refresh 토큰은 HttpOnly + Secure + SameSite=Strict 쿠키로 준다. 쿠키 경로는
 * {@code /api/auth} 라서 다른 API 요청에는 실리지 않는다.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    static final String REFRESH_COOKIE = "copage_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 50) String name) {
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record UserResponse(UUID id, String email, String name, Instant createdAt, String color) {
        static UserResponse of(User user) {
            return new UserResponse(user.id(), user.email(), user.name(), user.createdAt(), user.color());
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }

    private final AuthService auth;
    private final AuthProperties properties;
    private final JwtDecoder jwtDecoder;
    private final OriginCheck origins;
    private final OAuthProviders providers;

    AuthController(AuthService auth, AuthProperties properties, JwtDecoder jwtDecoder, WebProperties web,
            OAuthProviders providers) {
        this.auth = auth;
        this.providers = providers;
        this.properties = properties;
        this.jwtDecoder = jwtDecoder;
        this.origins = new OriginCheck(web.allowedOrigins());
    }

    private static ResponseEntity<ProblemDetail> foreignOrigin() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "허용하지 않은 출처의 요청이다"));
    }

    /** 켜진 다른 서비스 로그인 (A-4, D-53). 로그인 화면이 단추를 그린다. */
    @GetMapping("/providers")
    List<String> providers() {
        return providers.ids();
    }

    @PostMapping("/signup")
    ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        // BCrypt 는 72 바이트까지만 본다. 한글 비밀번호는 글자 수가 64 보다 적어도 이를 넘을 수 있다.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 UTF-8 로 72 바이트를 넘을 수 없다");
        }
        User user = auth.signup(request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(user));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(auth.login(request.email(), request.password()));
    }

    /**
     * Refresh 토큰을 새로 바꾸고 Access 토큰을 다시 준다. 실패하면 쿠키를 지운다. 다만 동시 재발급(409)일 때는 지우지 않는다.
     * 먼저 온 요청이 내려 준 새 쿠키까지 지워 버리기 때문이다.
     *
     * <p>쿠키를 쓰는 경로라서 SameSite=Strict 에 더해 Origin 을 검사한다 (9.2). CORS 필터가 먼저 막고, CORS 설정이 바뀌어도
     * 풀리지 않도록 여기서도 본다(OriginCheck, SEC-4).
     */
    @PostMapping("/refresh")
    ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest request) {
        if (!origins.allows(request)) {
            return foreignOrigin();
        }
        try {
            return withSession(auth.refresh(refreshToken));
        } catch (AuthException e) {
            ProblemDetail problem = AuthExceptionHandler.problem(e);
            if (e.kind() == AuthException.Kind.REFRESH_RACE) {
                return ResponseEntity.status(problem.getStatus()).body(problem);
            }
            return ResponseEntity.status(problem.getStatus())
                    .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                    .body(problem);
        }
    }

    /** Refresh 토큰을 지우고, Authorization 헤더의 Access 토큰이 아직 유효하면 남은 시간 동안 막는다. */
    @PostMapping("/logout")
    ResponseEntity<?> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        if (!origins.allows(request)) {
            return foreignOrigin();
        }
        String jti = null;
        Instant expiresAt = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Jwt jwt = jwtDecoder.decode(authorization.substring("Bearer ".length()));
                jti = jwt.getId();
                expiresAt = jwt.getExpiresAt();
            } catch (JwtException e) {
                // 이미 만료되었거나 막힌 토큰이다. 막을 필요가 없다.
            }
        }
        auth.logout(refreshToken, jti, expiresAt);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<TokenResponse> withSession(AuthService.Session session) {
        long expiresIn = Duration.between(Instant.now(), session.accessTokenExpiresAt()).toSeconds();
        TokenResponse body = new TokenResponse(
                session.accessToken(), "Bearer", Math.max(0, expiresIn), UserResponse.of(session.user()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(properties, session.refreshToken()).toString())
                .body(body);
    }

    /** Refresh 토큰 쿠키. 다른 서비스 계정 로그인(OAuthLoginHandlers)도 같은 것을 쓴다. */
    static ResponseCookie refreshCookie(AuthProperties properties, String refreshToken) {
        return ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(properties.refreshTokenTtl())
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }
}
