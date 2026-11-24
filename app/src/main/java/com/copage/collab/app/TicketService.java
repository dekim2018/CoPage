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

    /**
     * 권한 검사를 마친 쪽이 부른다. 티켓은 ttl 동안 한 번만 쓸 수 있다.
     *
     * @param name 접속자 목록과 남의 커서에 보일 이름
     */
    public String issue(UUID userId, UUID docId, PageRole role, String name) {
        return issue(new WsTicket(userId, docId, role, name));
    }

    /** 공유 링크로 들어온 연결의 티켓. 링크가 꺼지거나 바뀌면 그 연결은 등급을 다시 계산해 닫힌다 (D-41). */
    public String issueViaShareLink(UUID userId, UUID docId, PageRole role, String name, UUID shareLinkId) {
        return issue(new WsTicket(userId, docId, role, name, shareLinkId));
    }

    private String issue(WsTicket value) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(ticket, value, ttl);
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
