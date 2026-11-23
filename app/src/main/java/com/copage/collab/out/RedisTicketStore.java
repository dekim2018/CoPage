package com.copage.collab.out;

import com.copage.collab.app.TicketStore;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.WsTicket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 에 두는 1회용 티켓 (docs/07-SECURITY.md 9.3). 꺼낼 때 GETDEL 로 지운다. 여러 노드가 같은 티켓을 두 번 쓸 수 없다.
 *
 * <p>값은 {@code 사용자 ID|문서 ID|권한|이름} 이다. 이름에 | 가 들어 있어도 되도록 이름을 맨 뒤에 두고 네 조각까지만 자른다.
 */
public final class RedisTicketStore implements TicketStore {

    private final StringRedisTemplate redis;

    public RedisTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String ticket, WsTicket value, Duration ttl) {
        redis.opsForValue().set(
                key(ticket), value.userId() + "|" + value.docId() + "|" + value.role() + "|" + value.name(), ttl);
    }

    @Override
    public Optional<WsTicket> take(String ticket) {
        String value = redis.opsForValue().getAndDelete(key(ticket));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 4);
        return Optional.of(new WsTicket(
                UUID.fromString(parts[0]), UUID.fromString(parts[1]), PageRole.valueOf(parts[2]), parts[3]));
    }

    private static String key(String ticket) {
        return "copage:ws-ticket:" + ticket;
    }
}
