package com.copage.workspace.in;

import com.copage.permission.domain.PageLevel;
import com.copage.workspace.app.WorkspaceRepository.Membership;
import com.copage.workspace.app.WorkspaceRepository.PageSummary;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.WorkspaceRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 워크스페이스, 멤버, 페이지 트리 (docs/09-API.md 11.2). */
@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController {

    record CreateWorkspaceRequest(@NotBlank @Size(max = 100) String name) {
    }

    record WorkspaceResponse(UUID id, String name, WorkspaceRole role) {
        static WorkspaceResponse of(Membership membership) {
            return new WorkspaceResponse(
                    membership.workspace().id(), membership.workspace().name(), membership.role());
        }
    }

    record AddMemberRequest(@NotBlank @Email String email, @NotNull WorkspaceRole role) {
    }

    record MemberResponse(UUID userId, String email, String name, WorkspaceRole role) {
        static MemberResponse of(WorkspaceService.MemberView member) {
            return new MemberResponse(member.userId(), member.email(), member.name(), member.role());
        }
    }

    /**
     * @param parentId 최상위면 비운다
     * @param afterId 이 형제 바로 뒤에 둔다. 비우면 맨 뒤
     */
    record CreatePageRequest(UUID parentId, UUID afterId) {
    }

    /**
     * @param hasChildren 보관되지 않은 하위 페이지가 있는지. 그 하위 페이지를 내가 볼 수 있는지는 펼쳐 봐야 안다
     * @param level 이 페이지에 대한 내 등급
     */
    record PageResponse(UUID id, UUID workspaceId, UUID parentId, String title, boolean hasChildren, PageLevel level) {
        static PageResponse of(PageSummary summary, PageLevel level) {
            Page page = summary.page();
            return new PageResponse(
                    page.id(), page.workspaceId(), page.parentId(), page.title(), summary.hasChildren(), level);
        }
    }

    private final WorkspaceService workspaces;

    WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    List<WorkspaceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return workspaces.list(userId(jwt)).stream().map(WorkspaceResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.of(workspaces.create(userId(jwt), request.name()));
    }

    @GetMapping("/{workspaceId}/members")
    List<MemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return workspaces.members(userId(jwt), workspaceId).stream().map(MemberResponse::of).toList();
    }

    /** 가입한 사용자를 이메일로 바로 넣는다. 초대 메일(invites)이 생기면 그쪽으로 옮긴다. */
    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    MemberResponse addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddMemberRequest request) {
        return MemberResponse.of(workspaces.addMember(userId(jwt), workspaceId, request.email(), request.role()));
    }

    @GetMapping("/{workspaceId}/pages")
    List<PageResponse> pages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID parentId) {
        return workspaces.children(userId(jwt), workspaceId, parentId).stream()
                .map(child -> PageResponse.of(child.summary(), child.level()))
                .toList();
    }

    @PostMapping("/{workspaceId}/pages")
    @ResponseStatus(HttpStatus.CREATED)
    PageResponse createPage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestBody(required = false) CreatePageRequest request) {
        CreatePageRequest body = request == null ? new CreatePageRequest(null, null) : request;
        Page page = workspaces.createPage(userId(jwt), workspaceId, body.parentId(), body.afterId());
        // 만든 사람은 부모의 등급을 물려받는다. 이 페이지에 따로 준 권한은 아직 없다.
        return PageResponse.of(new PageSummary(page, false), workspaces.page(userId(jwt), page.id()).level());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
