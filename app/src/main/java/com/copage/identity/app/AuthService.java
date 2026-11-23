package com.copage.identity.app;

import com.copage.identity.app.AuthException.Kind;
import com.copage.identity.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * 가입, 로그인, 토큰 재발급, 로그아웃 (docs/07-SECURITY.md 9.1).
 *
 * <ul>
 *   <li>Access 토큰: JWT HS256. 클레임은 사용자 ID(sub)와 토큰 ID(jti), 발급과 만료 시각
 *   <li>Refresh 토큰: 무작위 32 바이트. 저장소에는 SHA-256 해시만 둔다. 재발급할 때마다 새로 바꾸고, 이미 쓴 토큰이 다시
 *       오면 탈취로 보고 그 사용자의 Refresh 토큰과 그때까지 발급한 Access 토큰을 모두 막는다
 * </ul>
 */
public final class AuthService {

    /** 토큰 유효 시간. */
    public record TokenSettings(Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    /** 로그인이나 재발급의 결과. refreshToken 은 쿠키로만 내보낸다. */
    public record Session(User user, String accessToken, Instant accessTokenExpiresAt, String refreshToken) {
    }

    // 없는 이메일로 로그인해도 비밀번호 확인에 걸리는 시간이 같도록 이 값과 비교한다. 가입 여부를 시간으로 알아내지 못하게 한다.
    private final String dummyHash;

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final AccessTokenRevocations revocations;
    private final LoginAttempts attempts;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final TokenSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            UserRepository users,
            RefreshTokenStore refreshTokens,
            AccessTokenRevocations revocations,
            LoginAttempts attempts,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            TokenSettings settings,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.revocations = revocations;
        this.attempts = attempts;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.settings = settings;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** 이메일은 앞뒤 공백을 빼고 소문자로 바꿔 저장하고 비교한다. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public User signup(String email, String password, String name) {
        User user = new User(UUID.randomUUID(), normalizeEmail(email), name.trim(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            users.create(user, passwordEncoder.encode(password));
        } catch (UserRepository.DuplicateEmailException e) {
            throw new AuthException(Kind.DUPLICATE_EMAIL, "이미 가입한 이메일이다");
        }
        return user;
    }

    public Session login(String email, String password) {
        String normalized = normalizeEmail(email);
        if (attempts.isLocked(normalized)) {
            throw new AuthException(Kind.LOCKED, "로그인 실패가 많아 잠시 잠겼다. 잠시 뒤 다시 시도한다");
        }
        Optional<UserRepository.Credentials> credentials = users.findByEmail(normalized);
        String hash = credentials.map(UserRepository.Credentials::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (credentials.isEmpty() || !matches) {
            // 없는 이메일도 실패로 센다. 세지 않으면 잠기는지 여부로 가입 여부를 알 수 있다.
            attempts.recordFailure(normalized);
            throw new AuthException(Kind.BAD_CREDENTIALS, "이메일이나 비밀번호가 맞지 않다");
        }
        attempts.reset(normalized);
        return issue(credentials.get().user());
    }

    /** Refresh 토큰으로 새 토큰을 받는다. 쓴 Refresh 토큰은 다시 쓸 수 없다. */
    public Session refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "Refresh 토큰이 없다");
        }
        return switch (refreshTokens.use(hash(refreshToken))) {
            case RefreshTokenStore.Use.Active active -> {
                User user = users.findById(active.userId())
                        .orElseThrow(() -> new AuthException(Kind.INVALID_REFRESH_TOKEN, "사용자가 없다"));
                yield issue(user);
            }
            case RefreshTokenStore.Use.Reused reused -> {
                // 이미 쓴 토큰이 다시 왔다. 누군가 훔친 토큰을 썼거나, 훔친 쪽이 먼저 썼다. 어느 쪽인지 알 수 없으므로
                // 이 사용자의 모든 세션을 끊는다.
                revokeAllSessions(reused.userId());
                throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "이미 쓴 Refresh 토큰이다. 모든 세션을 끊었다");
            }
            case RefreshTokenStore.Use.Unknown unknown ->
                    throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "Refresh 토큰이 없거나 만료되었다");
        };
    }

    /**
     * Refresh 토큰을 지우고, 지금 쓰던 Access 토큰을 남은 유효 시간 동안 막는다. 둘 다 없어도 된다.
     *
     * @param accessTokenId 지금 Access 토큰의 jti
     * @param accessTokenExpiresAt 지금 Access 토큰의 만료 시각
     */
    public void logout(String refreshToken, String accessTokenId, Instant accessTokenExpiresAt) {
        if (refreshToken != null && !refreshToken.isEmpty()) {
            refreshTokens.delete(hash(refreshToken));
        }
        if (accessTokenId != null && accessTokenExpiresAt != null) {
            Duration remaining = Duration.between(clock.instant(), accessTokenExpiresAt);
            if (!remaining.isNegative() && !remaining.isZero()) {
                revocations.block(accessTokenId, remaining);
            }
        }
    }

    public User me(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new AuthException(Kind.NOT_FOUND, "사용자가 없다"));
    }

    public User rename(UUID userId, String name) {
        me(userId);
        users.rename(userId, name.trim());
        return me(userId);
    }

    /** Access 토큰을 받아들여도 되는지. 리소스 서버의 토큰 검증기가 부른다. */
    public boolean isRevoked(UUID userId, String jti, Instant issuedAt) {
        if (jti != null && revocations.isBlocked(jti)) {
            return true;
        }
        Optional<Instant> until = revocations.revokedUntil(userId);
        return until.isPresent() && issuedAt != null && !issuedAt.isAfter(until.get());
    }

    private void revokeAllSessions(UUID userId) {
        refreshTokens.revokeAll(userId);
        // JWT 의 발급 시각은 초 단위다. 같은 초에 발급한 토큰까지 막으려고 초 단위로 내려 비교한다.
        revocations.revokeIssuedUntil(userId, clock.instant().truncatedTo(ChronoUnit.SECONDS), settings.accessTokenTtl());
    }

    private Session issue(User user) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.id().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        String accessToken = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(hash(refreshToken), user.id(), settings.refreshTokenTtl());
        return new Session(user, accessToken, expiresAt, refreshToken);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
