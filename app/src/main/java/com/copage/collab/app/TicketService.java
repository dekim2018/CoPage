package com.copage.collab.app;

import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.WsTicket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** WebSocket 연결에 쓰는 1회용 티켓을 만들고 확인한다 (docs/07-SECURITY.md 9.3). */
public final class TicketService {

    private final TicketStore store;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public TicketService(TicketStore store, Duration ttl) {
        this.store = store;
        this.ttl = ttl;
    }

    /** 권한 검사를 마친 쪽이 부른다. 티켓은 ttl 동안 한 번만 쓸 수 있다. */
    public String issue(UUID userId, UUID docId, PageRole role) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(ticket, new WsTicket(userId, docId, role), ttl);
        return ticket;
    }

    /** 티켓을 쓰고 지운다. 다른 문서의 티켓이면 쓰지 못한다. */
    public Optional<WsTicket> redeem(String ticket, UUID docId) {
        if (ticket == null || ticket.isEmpty()) {
            return Optional.empty();
        }
        return store.take(ticket).filter(value -> value.docId().equals(docId));
    }
}
