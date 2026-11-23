package com.copage.collab.out;

import com.copage.collab.app.TicketStore;
import com.copage.collab.domain.WsTicket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트용 메모리 티켓 저장소. */
public final class InMemoryTicketStore implements TicketStore {

    private record Entry(WsTicket value, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTicketStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(String ticket, WsTicket value, Duration ttl) {
        Instant now = clock.instant();
        // 쓰지 않고 만료된 티켓이 쌓이지 않게 저장할 때마다 정리한다.
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        entries.put(ticket, new Entry(value, now.plus(ttl)));
    }

    @Override
    public Optional<WsTicket> take(String ticket) {
        Entry entry = entries.remove(ticket);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }
}
