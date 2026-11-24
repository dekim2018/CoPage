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

    private static final java.util.regex.Pattern COLOR_FIELD = java.util.regex.Pattern.compile("-|#[0-9a-f]{6}");

    public RedisTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String ticket, WsTicket value, Duration ttl) {
        // 이름은 '|' 를 담을 수 있어 맨 뒤에 둔다. 공유 링크나 색이 없으면 '-'.
        String link = value.shareLinkId() == null ? "-" : value.shareLinkId().toString();
        String color = value.color() == null ? "-" : value.color();
        redis.opsForValue().set(
                key(ticket),
                value.userId() + "|" + value.docId() + "|" + value.role() + "|" + link + "|" + color + "|" + value.name(),
                ttl);
    }

    @Override
    public Optional<WsTicket> take(String ticket) {
        String value = redis.opsForValue().getAndDelete(key(ticket));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 6);
        UUID link = parts[3].equals("-") ? null : UUID.fromString(parts[3]);
        // 색 칸이 없는 예전 형식(배포 중 30초 안에 남은 티켓)이면 다섯째 칸부터가 이름이다.
        if (parts.length < 6 || !COLOR_FIELD.matcher(parts[4]).matches()) {
            return Optional.of(new WsTicket(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    PageRole.valueOf(parts[2]), value.split("\\|", 5)[4], link));
        }
        String color = parts[4].equals("-") ? null : parts[4];
        return Optional.of(new WsTicket(
                UUID.fromString(parts[0]), UUID.fromString(parts[1]), PageRole.valueOf(parts[2]), parts[5], link, color));
    }

    private static String key(String ticket) {
        return "copage:ws-ticket:" + ticket;
    }
}
