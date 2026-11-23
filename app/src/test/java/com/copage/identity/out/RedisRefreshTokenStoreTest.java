package com.copage.identity.out;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.identity.app.RefreshTokenStore.Use;
import com.copage.support.TestInfra;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
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
    private final RedisRefreshTokenStore store =
            new RedisRefreshTokenStore(redis, Duration.ofMillis(300), Duration.ofSeconds(2), Clock.systemUTC());
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

    /** 테스트끼리 같은 Redis 를 쓰므로 해시에 이 테스트의 사용자를 붙인다. */
    private String h(String name) {
        return user + "-" + name;
    }

    private Use rotate(String hash) {
        return store.rotate(hash, UUID.randomUUID().toString(), TTL);
    }

    @Test
    void 처음_쓰면_Active_유예_안에_다시_오면_RecentlyUsed_지나면_Reused() throws Exception {
        store.save("h1", user, TTL);

        assertEquals(new Use.Active(user), rotate("h1"));
        assertEquals(new Use.RecentlyUsed(user), rotate("h1"));
        Thread.sleep(400);
        assertEquals(new Use.Reused(user), rotate("h1"));
        assertEquals(new Use.Unknown(), rotate("없는 해시"));
    }

    @Test
    void 처음_쓸_때만_새_토큰을_같은_주인으로_저장한다() {
        store.save(h("h1"), user, TTL);

        assertEquals(new Use.Active(user), store.rotate(h("h1"), h("h2"), TTL));
        assertEquals(Optional.of(user), store.owner(h("h2")));
        assertEquals(Optional.of(user), store.owner(h("h1")), "쓴 토큰도 주인은 알 수 있다");
        assertEquals(new Use.Active(user), store.rotate(h("h2"), h("h3"), TTL));

        // 이미 쓴 토큰으로는 새 토큰이 생기지 않는다.
        assertEquals(new Use.RecentlyUsed(user), store.rotate(h("h1"), h("h4"), TTL));
        assertEquals(Optional.empty(), store.owner(h("h4")));
        assertEquals(Optional.empty(), store.owner("없는 해시"));
    }

    @Test
    void 기한이_지난_뒤에_처리된_회전은_아무것도_바꾸지_않는다() {
        store.save(h("h1"), user, TTL);

        // Redis 가 멈췄다 돌아와 밀린 회전을 처리하는 경우다. 보낸 쪽은 이미 포기했다.
        assertEquals(new Use.Late(), store.rotate(h("h1"), h("h2"), TTL, System.currentTimeMillis() - 60_000));

        assertEquals(Optional.empty(), store.owner(h("h2")));
        assertEquals(Set.of(h("h1")), userSet());
        assertEquals(new Use.Active(user), rotate(h("h1")), "옛 토큰은 그대로 쓸 수 있다");
    }

    @Test
    void 기한_지남을_답으로_받으면_시계가_어긋난_것이라_기한_없이_다시_회전한다() {
        // 앱 서버 시계가 Redis 보다 한참 늦다. 기한이 늘 지난 것으로 보인다.
        Clock behind = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-10));
        RedisRefreshTokenStore skewed = new RedisRefreshTokenStore(redis, Duration.ofMillis(300), Duration.ofSeconds(2), behind);
        skewed.save(h("h1"), user, TTL);

        assertEquals(new Use.Active(user), skewed.rotate(h("h1"), h("h2"), TTL));
        assertEquals(Optional.of(user), skewed.owner(h("h2")));
    }

    @Test
    void 사용자_집합에는_아직_쓰지_않은_토큰만_남는다() {
        // 재발급을 여러 번 해도 집합은 커지지 않는다.
        store.save("h0", user, TTL);
        for (int i = 1; i <= 100; i++) {
            store.rotate("h" + (i - 1), "h" + i, TTL);
        }
        assertEquals(Set.of("h100"), userSet());

        // 로그아웃으로 지운 토큰도 빠진다.
        store.delete("h100");
        assertEquals(Set.of(), userSet());
    }

    @Test
    void 모든_세션을_끊으면_살아_있는_토큰은_없어지고_쓴_토큰은_계속_재사용으로_보인다() throws Exception {
        store.save("old", user, TTL);
        store.rotate("old", "a", TTL);
        store.save("b", user, TTL);

        store.revokeAll(user);

        assertEquals(new Use.Unknown(), rotate("a"));
        assertEquals(new Use.Unknown(), rotate("b"));
        Thread.sleep(400);
        assertEquals(new Use.Reused(user), rotate("old"));
    }
}
