package com.copage.identity.out;

import com.copage.identity.app.AccessTokenRevocations;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 에 두는 Access 토큰 차단 목록. 막아 둘 시간이 Access 토큰 유효 시간을 넘지 않으므로 키는 그 뒤 저절로 사라진다.
 *
 * <ul>
 *   <li>{@code copage:blocked-jti:{jti}}: 로그아웃한 토큰
 *   <li>{@code copage:revoked-until:{사용자 ID}}: 이 시각(초)이나 그 전에 발급한 토큰을 막는다
 * </ul>
 */
public final class RedisAccessTokenRevocations implements AccessTokenRevocations {

    private final StringRedisTemplate redis;

    public RedisAccessTokenRevocations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void block(String jti, Duration ttl) {
        redis.opsForValue().set("copage:blocked-jti:" + jti, "1", ttl);
    }

    @Override
    public boolean isBlocked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey("copage:blocked-jti:" + jti));
    }

    @Override
    public void revokeIssuedUntil(UUID userId, Instant at, Duration ttl) {
        redis.opsForValue().set("copage:revoked-until:" + userId, Long.toString(at.getEpochSecond()), ttl);
    }

    @Override
    public Optional<Instant> revokedUntil(UUID userId) {
        String value = redis.opsForValue().get("copage:revoked-until:" + userId);
        return value == null ? Optional.empty() : Optional.of(Instant.ofEpochSecond(Long.parseLong(value)));
    }
}
