package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.app.UserNames;
import com.copage.collab.domain.PageRole;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PagePermissions.Access;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * WebSocket 티켓 발급 (docs/09-API.md 11.4, docs/07-SECURITY.md 9.3). 문서 ID 는 곧 페이지 ID 다.
 *
 * <p>페이지 권한(9.4)을 계산해 그 등급을 티켓에 담는다. 페이지가 없거나 휴지통에 있거나 그 워크스페이스에 속하지 않으면 404, 속해
 * 있지만 등급이 없으면 403 이다. 페이지가 없는 문서 ID 로는 더 이상 새 문서를 열 수 없다.
 */
@RestController
class TicketController {

    private final TicketService tickets;
    private final UserNames names;
    private final PagePermissions permissions;

    TicketController(TicketService tickets, UserNames names, PagePermissions permissions) {
        this.tickets = tickets;
        this.names = names;
        this.permissions = permissions;
    }

    @PostMapping("/api/docs/{docId}/ws-ticket")
    Map<String, Object> issue(@PathVariable UUID docId, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PageRole role = switch (permissions.access(userId, docId)) {
            case Access.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> PageRole.valueOf(granted.level().name());
        };
        return Map.of("ticket", tickets.issue(userId, docId, role, names.nameOf(userId)), "role", role);
    }
}
