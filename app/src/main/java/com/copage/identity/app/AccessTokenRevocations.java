package com.copage.identity.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Access 토큰을 유효 시간 전에 막는 포트. Access 토큰은 서버에 저장하지 않으므로 막을 것만 기록한다.
 *
 * <ul>
 *   <li>로그아웃: 그 토큰의 jti 를 남은 유효 시간 동안 막는다 (docs/07-SECURITY.md 9.1)
 *   <li>모든 세션 끊기: 그 사용자에게 그 시각까지 발급한 토큰을 모두 막는다. Refresh 토큰 재사용을 알아챘을 때 쓴다
 * </ul>
 */
public interface AccessTokenRevocations {

    void block(String jti, Duration ttl);

    boolean isBlocked(String jti);

    /** at 이나 그 전에 발급한 이 사용자의 토큰을 막는다. ttl 은 Access 토큰 유효 시간이면 된다. */
    void revokeIssuedUntil(UUID userId, Instant at, Duration ttl);

    Optional<Instant> revokedUntil(UUID userId);
}
