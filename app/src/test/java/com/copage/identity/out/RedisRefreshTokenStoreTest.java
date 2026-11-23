package com.copage.identity.out;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.identity.app.RefreshTokenStore.Use;
import com.copage.support.TestInfra;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Refresh 토큰 저장소를 실제 Redis 에 대고 확인한다. */
class RedisRefreshTokenStoreTest {

    private static final Duration TTL = Duration.ofDays(14);

    private final LettuceConnectionFactory factory = connect();
    private final StringRedisTemplate redis = new StringRedisTemplate(factory);
    private final RedisRefreshTokenStore store = new RedisRefreshTokenStore(redis, Duration.ofMillis(300));
    private final UUID user = UUID.randomUUID();

    private static LettuceConnectionFactory connect() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(TestInfra.REDIS.getHost(), TestInfra.REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @AfterEach
    void close() {
        factory.destroy();
    }

    private Set<String> userSet() {
        return redis.opsForSet().members("copage:user-refresh:" + user);
    }

    @Test
    void 처음_쓰면_Active_유예_안에_다시_오면_RecentlyUsed_지나면_Reused() throws Exception {
        store.save("h1", user, TTL);

        assertEquals(new Use.Active(user), store.use("h1"));
        assertEquals(new Use.RecentlyUsed(user), store.use("h1"));
        Thread.sleep(400);
        assertEquals(new Use.Reused(user), store.use("h1"));
        assertEquals(new Use.Unknown(), store.use("없는 해시"));
    }

    @Test
    void 사용자_집합에는_아직_쓰지_않은_토큰만_남는다() {
        // 재발급을 여러 번 해도 집합은 커지지 않는다.
        store.save("h0", user, TTL);
        for (int i = 1; i <= 100; i++) {
            store.use("h" + (i - 1));
            store.save("h" + i, user, TTL);
        }
        assertEquals(Set.of("h100"), userSet());

        // 로그아웃으로 지운 토큰도 빠진다.
        store.delete("h100");
        assertEquals(Set.of(), userSet());
    }

    @Test
    void 모든_세션을_끊으면_살아_있는_토큰은_없어지고_쓴_토큰은_계속_재사용으로_보인다() throws Exception {
        store.save("old", user, TTL);
        store.use("old");
        store.save("a", user, TTL);
        store.save("b", user, TTL);

        store.revokeAll(user);

        assertEquals(new Use.Unknown(), store.use("a"));
        assertEquals(new Use.Unknown(), store.use("b"));
        Thread.sleep(400);
        assertEquals(new Use.Reused(user), store.use("old"));
    }
}
