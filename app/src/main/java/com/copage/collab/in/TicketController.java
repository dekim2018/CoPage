package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.app.UserNames;
import com.copage.collab.domain.PageRole;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebSocket 티켓 발급 (docs/09-API.md 11.3, docs/07-SECURITY.md 9.3). 로그인한 사용자만 받는다.
 *
 * <p>권한 계산(9.4)은 3단계에서 붙는다. 그때까지는 로그인한 사용자에게 어느 문서든 EDIT 티켓을 준다.
 */
@RestController
class TicketController {

    private final TicketService tickets;
    private final UserNames names;

    TicketController(TicketService tickets, UserNames names) {
        this.tickets = tickets;
        this.names = names;
    }

    @PostMapping("/api/docs/{docId}/ws-ticket")
    Map<String, Object> issue(@PathVariable UUID docId, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PageRole role = PageRole.EDIT;
        return Map.of("ticket", tickets.issue(userId, docId, role, names.nameOf(userId)), "role", role);
    }
}
