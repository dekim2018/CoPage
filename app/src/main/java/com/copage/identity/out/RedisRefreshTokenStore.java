package com.copage.identity.out;

import com.copage.identity.app.RefreshTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 에 두는 Refresh 토큰.
 *
 * <ul>
 *   <li>{@code copage:refresh:{해시}}: {@code active:{사용자 ID}} 또는 쓴 뒤의 {@code used:{사용자 ID}}. 만료는 토큰 유효 시간
 *   <li>{@code copage:user-refresh:{사용자 ID}}: 그 사용자의 토큰 해시 집합. 모든 세션을 끊을 때 쓴다
 * </ul>
 */
public final class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String ACTIVE = "active:";
    private static final String USED = "used:";

    // 확인과 표시를 한 번에 한다. 같은 토큰으로 동시에 두 번 재발급해도 둘 다 처음으로 판정되지 않는다.
    private static final RedisScript<String> USE = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return ''
            end
            if string.sub(value, 1, 7) == 'active:' then
              redis.call('SET', KEYS[1], 'used:' .. string.sub(value, 8), 'KEEPTTL')
            end
            return value
            """, String.class);

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String tokenHash, UUID userId, Duration ttl) {
        redis.opsForValue().set(tokenKey(tokenHash), ACTIVE + userId, ttl);
        redis.opsForSet().add(userKey(userId), tokenHash);
        redis.expire(userKey(userId), ttl);
    }

    @Override
    public Use use(String tokenHash) {
        String value = redis.execute(USE, List.of(tokenKey(tokenHash)));
        if (value == null || value.isEmpty()) {
            return new Use.Unknown();
        }
        if (value.startsWith(ACTIVE)) {
            return new Use.Active(UUID.fromString(value.substring(ACTIVE.length())));
        }
        if (value.startsWith(USED)) {
            return new Use.Reused(UUID.fromString(value.substring(USED.length())));
        }
        return new Use.Unknown();
    }

    @Override
    public void revokeAll(UUID userId) {
        Set<String> hashes = redis.opsForSet().members(userKey(userId));
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(RedisRefreshTokenStore::tokenKey).toList());
        }
        redis.delete(userKey(userId));
    }

    @Override
    public void delete(String tokenHash) {
        redis.delete(tokenKey(tokenHash));
    }

    private static String tokenKey(String tokenHash) {
        return "copage:refresh:" + tokenHash;
    }

    private static String userKey(UUID userId) {
        return "copage:user-refresh:" + userId;
    }
}
