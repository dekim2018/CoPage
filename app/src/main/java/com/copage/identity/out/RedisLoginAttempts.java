package com.copage.identity.out;

import com.copage.identity.app.LoginAttempts;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 카운터로 세는 로그인 실패 (docs/07-SECURITY.md 9.1). 첫 실패부터 window 안에 maxFailures 번 실패하면 lock 동안
 * 잠근다.
 *
 * <ul>
 *   <li>{@code copage:login-fail:{이메일}}: 실패 횟수. 첫 실패에 window 만큼 만료를 건다
 *   <li>{@code copage:login-lock:{이메일}}: 잠금. lock 만큼 산다
 * </ul>
 */
public final class RedisLoginAttempts implements LoginAttempts {

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;
    private final Duration lock;

    public RedisLoginAttempts(StringRedisTemplate redis, int maxFailures, Duration window, Duration lock) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.window = window;
        this.lock = lock;
    }

    @Override
    public boolean isLocked(String email) {
        return Boolean.TRUE.equals(redis.hasKey(lockKey(email)));
    }

    @Override
    public void recordFailure(String email) {
        Long failures = redis.opsForValue().increment(failKey(email));
        if (failures != null && failures == 1) {
            redis.expire(failKey(email), window);
        }
        if (failures != null && failures >= maxFailures) {
            redis.opsForValue().set(lockKey(email), "1", lock);
            redis.delete(failKey(email));
        }
    }

    @Override
    public void reset(String email) {
        redis.delete(failKey(email));
    }

    private static String failKey(String email) {
        return "copage:login-fail:" + email;
    }

    private static String lockKey(String email) {
        return "copage:login-lock:" + email;
    }
}
