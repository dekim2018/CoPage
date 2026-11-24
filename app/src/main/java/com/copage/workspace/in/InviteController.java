package com.copage.workspace.in;

import com.copage.workspace.app.InviteService;
import com.copage.workspace.domain.Invite;
import com.copage.workspace.domain.WorkspaceRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/** 워크스페이스 초대 (docs/09-API.md 11.2, D-40). 모두 로그인이 필요하다. */
@RestController
class InviteController {

    record CreateInviteRequest(@NotBlank @Email String email, @NotNull WorkspaceRole role) {
    }

    /** @param token 초대 링크에 넣을 값. 만들 때만 돌려준다 */
    /** @param mailed 초대 메일을 보냈는지(메일 서버가 설정되어 있을 때). 아니면 화면이 링크를 건네라고 보인다 */
    record CreatedInvite(UUID id, String email, WorkspaceRole role, Instant expiresAt, String token, boolean mailed) {
    }

    record PendingInvite(UUID id, String email, WorkspaceRole role, Instant createdAt, Instant expiresAt) {
        static PendingInvite of(Invite invite) {
            return new PendingInvite(invite.id(), invite.email(), invite.role(), invite.createdAt(), invite.expiresAt());
        }
    }

    record InvitePreview(UUID workspaceId, String workspaceName, String email, WorkspaceRole role, Instant expiresAt) {
    }

    private final InviteService invites;

    InviteController(InviteService invites) {
        this.invites = invites;
    }

    @PostMapping("/api/workspaces/{workspaceId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedInvite create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateInviteRequest request) {
        InviteService.Created created = invites.create(userId(jwt), workspaceId, request.email(), request.role());
        Invite invite = created.invite();
        return new CreatedInvite(invite.id(), invite.email(), invite.role(), invite.expiresAt(), created.token(),
                created.mailed());
    }

    @GetMapping("/api/workspaces/{workspaceId}/invites")
    List<PendingInvite> pending(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return invites.pending(userId(jwt), workspaceId).stream().map(PendingInvite::of).toList();
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID inviteId) {
        invites.revoke(userId(jwt), workspaceId, inviteId);
    }

    @GetMapping("/api/invites/{token}")
    InvitePreview preview(@PathVariable String token) {
        InviteService.Preview preview = invites.preview(token);
        Invite invite = preview.invite();
        return new InvitePreview(invite.workspaceId(), preview.workspace().name(), invite.email(), invite.role(),
                invite.expiresAt());
    }

    @PostMapping("/api/invites/{token}/accept")
    Map<String, UUID> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return Map.of("workspaceId", invites.accept(userId(jwt), token));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
