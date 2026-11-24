package com.copage.workspace.in;

import com.copage.permission.domain.PageLevel;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.domain.Page;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 페이지 하나 (docs/09-API.md 11.2). */
@RestController
class PageController {

    record PathEntry(UUID id, String title) {
    }

    /**
     * @param restricted 상속을 끊었는지. 공유 창이 보인다
     * @param path 내가 볼 수 있는 조상. 루트 쪽부터. 볼 수 없는 조상은 빠진다
     */
    record PageDetail(
            UUID id,
            UUID workspaceId,
            UUID parentId,
            String title,
            boolean restricted,
            PageLevel level,
            List<PathEntry> path) {
    }

    private final WorkspaceService workspaces;

    PageController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /** @param afterId 이 형제 바로 뒤. 없으면 맨 뒤 */
    record MoveRequest(UUID parentId, UUID afterId) {
    }

    @PostMapping("/api/pages/{pageId}/move")
    PageDetail move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId, @RequestBody MoveRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        workspaces.movePage(userId, pageId, request.parentId(), request.afterId());
        return page(jwt, pageId);
    }

    /** 휴지통으로 보낸다. 휴지통에 있는 페이지는 없는 페이지와 같아 404 다. */
    @DeleteMapping("/api/pages/{pageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trash(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        workspaces.trashPage(UUID.fromString(jwt.getSubject()), pageId);
    }

    @PostMapping("/api/pages/{pageId}/restore")
    PageDetail restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        workspaces.restorePage(UUID.fromString(jwt.getSubject()), pageId);
        return page(jwt, pageId);
    }

    @GetMapping("/api/pages/{pageId}")
    PageDetail page(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        WorkspaceService.PageView view = workspaces.page(UUID.fromString(jwt.getSubject()), pageId);
        Page page = view.page();
        return new PageDetail(page.id(), page.workspaceId(), page.parentId(), page.title(), page.restricted(), view.level(),
                view.path().stream().map(ancestor -> new PathEntry(ancestor.id(), ancestor.title())).toList());
    }
}
