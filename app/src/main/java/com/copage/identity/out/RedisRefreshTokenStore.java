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
 *   <li>{@code copage:refresh:{해시}}: {@code active:{사용자 ID}}, 쓴 뒤에는 {@code used:{사용자 ID}:{쓴 시각 ms}}. 만료는 토큰
 *       유효 시간
 *   <li>{@code copage:user-refresh:{사용자 ID}}: 그 사용자의 아직 쓰지 않은 토큰 해시 집합. 모든 세션을 끊을 때 쓴다. 토큰을
 *       쓰거나 지우면 집합에서도 빼므로 집합은 살아 있는 토큰 수만큼만 크다
 * </ul>
 *
 * <p>판정과 표시는 Lua 스크립트 하나로 한다. 같은 토큰으로 동시에 재발급해도 한 번만 처음으로 판정된다. 쓴 시각은 Redis 의
 * 시계(TIME)로 적고 비교해서 앱 서버마다 시계가 달라도 판정이 같다. 스크립트가 사용자별 집합 키를 값에서 만들어 쓰므로
 * Redis 클러스터에서는 쓸 수 없다. 노드 하나를 쓰는 지금 구성(docs/02-TECH-STACK.md)을 전제로 한다.
 */
public final class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String TOKEN_PREFIX = "copage:refresh:";
    private static final String USER_PREFIX = "copage:user-refresh:";

    // KEYS[1] 토큰 키, ARGV[1] 유예 시간(ms), ARGV[2] 사용자 집합 키 앞부분, ARGV[3] 토큰 해시.
    // 돌려주는 값: '' 없음, 'active:{uid}' 처음, 'recent:{uid}' 유예 안의 재사용, 'used:{uid}' 유예 밖의 재사용.
    private static final RedisScript<String> USE = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return ''
            end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            if string.sub(value, 1, 7) == 'active:' then
              local uid = string.sub(value, 8)
              redis.call('SET', KEYS[1], 'used:' .. uid .. ':' .. now, 'KEEPTTL')
              redis.call('SREM', ARGV[2] .. uid, ARGV[3])
              return value
            end
            local uid, usedAt = string.match(value, '^used:([^:]+):(%d+)$')
            if uid and now - tonumber(usedAt) <= tonumber(ARGV[1]) then
              return 'recent:' .. uid
            end
            if uid then
              return 'used:' .. uid
            end
            return ''
            """, String.class);

    // KEYS[1] 토큰 키, ARGV[1] 사용자 집합 키 앞부분, ARGV[2] 토큰 해시.
    private static final RedisScript<Long> DELETE = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if value and string.sub(value, 1, 7) == 'active:' then
              redis.call('SREM', ARGV[1] .. string.sub(value, 8), ARGV[2])
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration reuseGrace;

    /**
     * @param reuseGrace 쓴 토큰이 이 시간 안에 다시 오면 탈취가 아니라 동시 재발급(탭 두 개, 재시도)으로 본다
     */
    public RedisRefreshTokenStore(StringRedisTemplate redis, Duration reuseGrace) {
        this.redis = redis;
        this.reuseGrace = reuseGrace;
    }

    @Override
    public void save(String tokenHash, UUID userId, Duration ttl) {
        redis.opsForValue().set(tokenKey(tokenHash), "active:" + userId, ttl);
        redis.opsForSet().add(userKey(userId), tokenHash);
        redis.expire(userKey(userId), ttl);
    }

    @Override
    public Use use(String tokenHash) {
        String value = redis.execute(
                USE, List.of(tokenKey(tokenHash)), Long.toString(reuseGrace.toMillis()), USER_PREFIX, tokenHash);
        if (value == null || value.isEmpty()) {
            return new Use.Unknown();
        }
        int colon = value.indexOf(':');
        UUID userId = UUID.fromString(value.substring(colon + 1));
        return switch (value.substring(0, colon)) {
            case "active" -> new Use.Active(userId);
            case "recent" -> new Use.RecentlyUsed(userId);
            case "used" -> new Use.Reused(userId);
            default -> new Use.Unknown();
        };
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
        redis.execute(DELETE, List.of(tokenKey(tokenHash)), USER_PREFIX, tokenHash);
    }

    private static String tokenKey(String tokenHash) {
        return TOKEN_PREFIX + tokenHash;
    }

    private static String userKey(UUID userId) {
        return USER_PREFIX + userId;
    }
}
