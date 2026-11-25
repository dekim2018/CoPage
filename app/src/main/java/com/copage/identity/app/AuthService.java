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
import java.util.List;
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
 *       오면 탈취로 보고 그 사용자의 Refresh 토큰과 그때까지 발급한 Access 토큰을 모두 막는다. 쓴 직후 짧은 유예 시간
 *       안에 다시 온 것은 동시 재발급으로 보고 그 요청만 실패시킨다
 * </ul>
 */
public final class AuthService {

    /**
     * 토큰 유효 시간과 Access 토큰에 적는 값.
     *
     * @param issuer Access 토큰의 iss
     * @param audience Access 토큰의 aud
     */
    public record TokenSettings(Duration accessTokenTtl, Duration refreshTokenTtl, String issuer, String audience) {
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
        // 다른 서비스 계정으로만 가입한 사용자는 비밀번호가 없다. 같은 시간을 쓰고 실패한다.
        boolean hasPassword = credentials.map(c -> c.passwordHash() != null).orElse(false);
        String hash = hasPassword ? credentials.get().passwordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(password, hash);
        if (!hasPassword || !matches) {
            // 없는 이메일도 실패로 센다. 세지 않으면 잠기는지 여부로 가입 여부를 알 수 있다.
            attempts.recordFailure(normalized);
            throw new AuthException(Kind.BAD_CREDENTIALS, "이메일이나 비밀번호가 맞지 않다");
        }
        attempts.reset(normalized);
        return issue(credentials.get().user());
    }

    /**
     * 다른 서비스 계정으로 로그인한 사람 (A-4).
     *
     * @param provider 구글은 google, 깃허브는 github
     * @param subject 그 서비스 안의 변하지 않는 사용자 ID
     * @param emailVerified 그 서비스가 이메일을 확인했는지
     */
    public record ProviderLogin(String provider, String subject, String email, boolean emailVerified, String name) {
    }

    /**
     * 다른 서비스 계정으로 로그인한다 (A-4, D-53). 이어진 계정이 있으면 그 사용자로, 없으면 확인된 이메일로 새 사용자를 만든다.
     * 그 이메일로 비밀번호 가입한 계정이 있으면 잇지 않는다. 이 서비스의 비밀번호 가입은 이메일을 확인하지 않아서, 남의 이메일로
     * 먼저 가입해 둔 계정에 진짜 주인의 구글 로그인이 붙으면 계정을 빼앗긴다.
     *
     * @throws AuthException EMAIL_NOT_VERIFIED 이메일을 확인받지 않음, ACCOUNT_EXISTS 그 이메일의 비밀번호 계정이 있음
     */
    public Session loginWithProvider(ProviderLogin login) {
        Optional<User> linked = users.findByIdentity(login.provider(), login.subject());
        if (linked.isPresent()) {
            return issue(linked.get());
        }
        if (!login.emailVerified() || login.email() == null || login.email().isBlank()) {
            throw new AuthException(Kind.EMAIL_NOT_VERIFIED, "확인된 이메일이 없다");
        }
        String email = normalizeEmail(login.email());
        if (users.findByEmail(email).isPresent()) {
            throw new AuthException(Kind.ACCOUNT_EXISTS, "그 이메일로 가입한 계정이 있다. 비밀번호로 로그인한다");
        }
        String name = login.name() == null || login.name().isBlank() ? email.substring(0, email.indexOf('@')) : login.name();
        User user = new User(UUID.randomUUID(), email, name.length() > 50 ? name.substring(0, 50) : name.trim(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            users.createWithIdentity(user, login.provider(), login.subject());
        } catch (UserRepository.DuplicateEmailException e) {
            // 같은 계정의 로그인이 동시에 둘 들어와 다른 쪽이 먼저 만들었을 수 있다.
            return users.findByIdentity(login.provider(), login.subject())
                    .map(this::issue)
                    .orElseThrow(() -> new AuthException(Kind.ACCOUNT_EXISTS, "그 이메일로 가입한 계정이 있다"));
        }
        return issue(user);
    }

    /** Refresh 토큰으로 새 토큰을 받는다. 쓴 Refresh 토큰은 다시 쓸 수 없다. */
    public Session refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "Refresh 토큰이 없다");
        }
        String tokenHash = hash(refreshToken);
        // 회전하기 전에 사용자를 읽는다. 회전한 뒤에 DB 에서 실패하면 옛 토큰은 쓰였는데 새 토큰은 클라이언트에 닿지 않는다.
        UUID owner = refreshTokens.owner(tokenHash)
                .orElseThrow(() -> new AuthException(Kind.INVALID_REFRESH_TOKEN, "Refresh 토큰이 없거나 만료되었다"));
        Optional<User> user = users.findById(owner);
        String next = newRefreshToken();
        return switch (refreshTokens.rotate(tokenHash, hash(next), settings.refreshTokenTtl())) {
            case RefreshTokenStore.Use.Active active -> {
                if (user.isEmpty()) {
                    refreshTokens.delete(hash(next));
                    throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "사용자가 없다");
                }
                yield session(user.get(), next);
            }
            case RefreshTokenStore.Use.RecentlyUsed recent ->
                    // 탭 두 개나 재시도가 같은 쿠키로 동시에 왔다. 먼저 온 요청이 새 토큰을 쿠키로 내려 주었으니 이 요청만
                    // 실패시키고 세션은 건드리지 않는다.
                    throw new AuthException(Kind.REFRESH_RACE, "다른 요청이 방금 토큰을 재발급했다. 다시 시도한다");
            case RefreshTokenStore.Use.Reused reused -> {
                // 이미 쓴 토큰이 다시 왔다. 누군가 훔친 토큰을 썼거나, 훔친 쪽이 먼저 썼다. 어느 쪽인지 알 수 없으므로
                // 이 사용자의 모든 세션을 끊는다.
                revokeAllSessions(reused.userId());
                throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "이미 쓴 Refresh 토큰이다. 모든 세션을 끊었다");
            }
            case RefreshTokenStore.Use.Unknown unknown ->
                    throw new AuthException(Kind.INVALID_REFRESH_TOKEN, "Refresh 토큰이 없거나 만료되었다");
            case RefreshTokenStore.Use.Late late ->
                    throw new IllegalStateException("저장소가 기한 지남을 밖으로 돌려주었다");
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

    /** 커서 색을 바꾼다 (A-3). 정해 둔 색 중 하나다. 확인은 부르는 쪽(입구)이 한다. */
    public User recolor(UUID userId, String color) {
        me(userId);
        users.recolor(userId, color);
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
        String refreshToken = newRefreshToken();
        refreshTokens.save(hash(refreshToken), user.id(), settings.refreshTokenTtl());
        return session(user, refreshToken);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Session session(User user, String refreshToken) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.id().toString())
                .issuer(settings.issuer())
                .audience(List.of(settings.audience()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        String accessToken = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
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
