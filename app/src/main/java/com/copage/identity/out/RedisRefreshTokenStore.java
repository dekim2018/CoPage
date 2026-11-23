package com.copage.identity.out;

import com.copage.identity.app.RefreshTokenStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 * <p>판정과 표시와 새 토큰 저장은 Lua 스크립트 하나로 한다. 같은 토큰으로 동시에 재발급해도 한 번만 처음으로 판정된다. 쓴 시각은 Redis 의
 * 시계(TIME)로 적고 비교해서 앱 서버마다 시계가 달라도 판정이 같다. 스크립트가 사용자별 집합 키를 값에서 만들어 쓰므로
 * Redis 클러스터에서는 쓸 수 없다. 노드 하나를 쓰는 지금 구성(docs/02-TECH-STACK.md)을 전제로 한다.
 *
 * <p>Redis 가 멈췄다 돌아오면, 이미 받아 둔 명령은 보낸 쪽이 시간 초과로 포기한 뒤에도 처리된다(일시 정지한 Redis 로 확인했다).
 * 그래서 회전 스크립트에는 기한을 붙인다. Redis 시계로 기한이 지났으면 아무것도 바꾸지 않고 {@code late:} 를 돌려준다. 기한은
 * 명령 시간 한도의 절반이다. 한도 안에 답을 받을 수 있는 실행은 대개 기한 안에 일어나고, 기한을 넘긴 실행의 답은 아무도 기다리지
 * 않는다. 답으로 {@code late:} 를 받았다면 앱 서버와 Redis 의 시계가 어긋난 것이므로, 기한 없이 한 번 더 실행한다.
 */
public final class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String TOKEN_PREFIX = "copage:refresh:";
    private static final String USER_PREFIX = "copage:user-refresh:";

    // KEYS[1] 토큰 키, KEYS[2] 새 토큰 키, ARGV[1] 유예 시간(ms), ARGV[2] 사용자 집합 키 앞부분, ARGV[3] 토큰 해시,
    // ARGV[4] 새 토큰 해시, ARGV[5] 새 토큰 유효 시간(ms), ARGV[6] 기한(epoch ms, 0 이면 없음).
    // 돌려주는 값: 'late:' 기한 지남(바꾼 것 없음), '' 없음, 'active:{uid}' 처음(새 토큰 저장), 'recent:{uid}' 유예 안의 재사용,
    // 'used:{uid}' 유예 밖의 재사용.
    private static final RedisScript<String> ROTATE = RedisScript.of("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local deadline = tonumber(ARGV[6])
            if deadline > 0 and now > deadline then
              return 'late:'
            end
            local value = redis.call('GET', KEYS[1])
            if not value then
              return ''
            end
            if string.sub(value, 1, 7) == 'active:' then
              local uid = string.sub(value, 8)
              redis.call('SET', KEYS[1], 'used:' .. uid .. ':' .. now, 'KEEPTTL')
              redis.call('SREM', ARGV[2] .. uid, ARGV[3])
              redis.call('SET', KEYS[2], value, 'PX', ARGV[5])
              redis.call('SADD', ARGV[2] .. uid, ARGV[4])
              redis.call('PEXPIRE', ARGV[2] .. uid, ARGV[5])
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

    private static final System.Logger LOG = System.getLogger(RedisRefreshTokenStore.class.getName());

    private final StringRedisTemplate redis;
    private final Duration reuseGrace;
    private final Duration deadline;
    private final Clock clock;

    /**
     * @param reuseGrace 쓴 토큰이 이 시간 안에 다시 오면 탈취가 아니라 동시 재발급(탭 두 개, 재시도)으로 본다
     * @param commandTimeout Redis 명령 시간 한도. 회전 스크립트의 기한은 그 절반이다
     */
    public RedisRefreshTokenStore(StringRedisTemplate redis, Duration reuseGrace, Duration commandTimeout, Clock clock) {
        this.redis = redis;
        this.reuseGrace = reuseGrace;
        this.deadline = commandTimeout.dividedBy(2);
        this.clock = clock;
    }

    @Override
    public void save(String tokenHash, UUID userId, Duration ttl) {
        redis.opsForValue().set(tokenKey(tokenHash), "active:" + userId, ttl);
        redis.opsForSet().add(userKey(userId), tokenHash);
        redis.expire(userKey(userId), ttl);
    }

    @Override
    public Optional<UUID> owner(String tokenHash) {
        String value = redis.opsForValue().get(tokenKey(tokenHash));
        if (value == null) {
            return Optional.empty();
        }
        // 'active:{uid}' 또는 'used:{uid}:{ms}'
        String rest = value.substring(value.indexOf(':') + 1);
        int colon = rest.indexOf(':');
        return Optional.of(UUID.fromString(colon < 0 ? rest : rest.substring(0, colon)));
    }

    @Override
    public Use rotate(String tokenHash, String nextHash, Duration ttl) {
        Use use = rotate(tokenHash, nextHash, ttl, clock.millis() + deadline.toMillis());
        if (use instanceof Use.Late) {
            // 답을 받았으니 시간 초과는 아니다. Redis 시계가 앞서 있다.
            LOG.log(System.Logger.Level.WARNING, "Redis 시계가 앱 서버보다 " + deadline.toMillis()
                    + "ms 넘게 앞서 있다. 기한 없이 다시 회전한다");
            use = rotate(tokenHash, nextHash, ttl, 0);
        }
        return use;
    }

    /** 기한(epoch ms, 0 이면 없음)을 붙여 회전한다. 기한이 지났으면 {@link Use.Late} 이고 아무것도 바꾸지 않는다. */
    Use rotate(String tokenHash, String nextHash, Duration ttl, long deadlineMillis) {
        String value = redis.execute(
                ROTATE,
                List.of(tokenKey(tokenHash), tokenKey(nextHash)),
                Long.toString(reuseGrace.toMillis()),
                USER_PREFIX,
                tokenHash,
                nextHash,
                Long.toString(ttl.toMillis()),
                Long.toString(deadlineMillis));
        if ("late:".equals(value)) {
            return new Use.Late();
        }
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
