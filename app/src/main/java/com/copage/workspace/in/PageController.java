package com.copage.workspace.in;

import com.copage.permission.domain.PageLevel;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.domain.Page;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/api/pages/{pageId}")
    PageDetail page(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID pageId) {
        WorkspaceService.PageView view = workspaces.page(UUID.fromString(jwt.getSubject()), pageId);
        Page page = view.page();
        return new PageDetail(page.id(), page.workspaceId(), page.parentId(), page.title(), page.restricted(), view.level(),
                view.path().stream().map(ancestor -> new PathEntry(ancestor.id(), ancestor.title())).toList());
    }
}
