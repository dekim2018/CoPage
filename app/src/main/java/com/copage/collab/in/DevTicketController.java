package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.domain.PageRole;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발용 티켓 발급. dev 프로필에서만 켜진다.
 *
 * <p>로그인과 권한 계산이 아직 없어서, 누구든 원하는 사용자와 등급으로 티켓을 받는다. 로그인 작업에서 JWT 인증과 권한
 * 검사를 거치는 {@code POST /api/docs/{docId}/ws-ticket} (docs/09-API.md)으로 바꾸고 이 클래스는 지운다.
 */
@Profile("dev")
@RestController
class DevTicketController {

    record Request(UUID userId, PageRole role) {
    }

    private final TicketService tickets;

    DevTicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/dev/docs/{docId}/ws-ticket")
    Map<String, Object> issue(@PathVariable UUID docId, @RequestBody(required = false) Request request) {
        UUID userId = request != null && request.userId() != null ? request.userId() : UUID.randomUUID();
        PageRole role = request != null && request.role() != null ? request.role() : PageRole.EDIT;
        return Map.of("ticket", tickets.issue(userId, docId, role), "userId", userId, "role", role);
    }
}
