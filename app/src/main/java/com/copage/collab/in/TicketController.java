package com.copage.collab.in;

import com.copage.collab.app.TicketService;
import com.copage.collab.app.UserNames;
import com.copage.collab.domain.PageRole;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.app.PermissionException;
import com.copage.permission.app.ShareLinks;
import com.copage.permission.domain.PageLevel;
import java.security.SecureRandom;
import java.util.List;
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

    /** 링크로 들어온 비로그인 방문자에게 붙이는 이름. 접속자 목록에서 서로 구분하는 정도다. */
    private static final List<String> ANIMALS = List.of("수달", "고래", "여우", "부엉이", "판다", "다람쥐", "펭귄", "너구리");

    private final TicketService tickets;
    private final UserNames names;
    private final PagePermissions permissions;
    private final ShareLinks shareLinks;
    private final SecureRandom random = new SecureRandom();

    TicketController(TicketService tickets, UserNames names, PagePermissions permissions, ShareLinks shareLinks) {
        this.tickets = tickets;
        this.names = names;
        this.permissions = permissions;
        this.shareLinks = shareLinks;
    }

    @PostMapping("/api/docs/{docId}/ws-ticket")
    Map<String, Object> issue(@PathVariable UUID docId, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PageRole role = switch (permissions.access(userId, docId)) {
            case Access.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> PageRole.valueOf(granted.level().name());
        };
        return Map.of("ticket", tickets.issue(userId, docId, role, names.nameOf(userId), names.colorOf(userId)),
                "role", role);
    }

    /**
     * 공유 링크로 여는 연결의 티켓 (D-41). 로그인하지 않아도 된다. 로그인했으면 자기 등급과 링크 등급 중 높은 것, 아니면 링크
     * 등급이고 이 연결에만 쓰는 임시 ID 와 이름(익명 수달 등)을 붙인다. 링크가 꺼지거나 바뀌면 이 연결은 등급을 다시 계산한다.
     */
    @PostMapping("/api/share/{token}/ws-ticket")
    Map<String, Object> issueViaShareLink(@PathVariable String token, @AuthenticationPrincipal Jwt jwt) {
        ShareLinks.Opened opened;
        try {
            opened = shareLinks.open(token);
        } catch (PermissionException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        UUID docId = opened.link().pageId();
        PageLevel level = opened.link().level();
        UUID userId;
        String name;
        String color = null;
        if (jwt != null) {
            userId = UUID.fromString(jwt.getSubject());
            name = names.nameOf(userId);
            color = names.colorOf(userId);
            if (permissions.access(userId, docId) instanceof Access.Granted granted) {
                level = PageLevel.max(level, granted.level());
            }
        } else {
            userId = UUID.randomUUID();
            name = "익명 " + ANIMALS.get(random.nextInt(ANIMALS.size()));
        }
        PageRole role = PageRole.valueOf(level.name());
        String ticket = tickets.issueViaShareLink(userId, docId, role, name, opened.link().id(), color);
        return Map.of("ticket", ticket, "role", role, "pageId", docId, "title", opened.title());
    }
}
