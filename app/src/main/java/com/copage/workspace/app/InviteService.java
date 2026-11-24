package com.copage.workspace.app;

import com.copage.identity.app.AuthService;
import com.copage.identity.app.UserDirectory;
import com.copage.identity.domain.User;
import com.copage.permission.app.PageGrants;
import com.copage.workspace.app.WorkspaceException.Kind;
import com.copage.workspace.domain.Invite;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 워크스페이스 초대 (docs/09-API.md 11.2, D-40). 메일은 보내지 않는다(08-FEATURES). OWNER 가 초대 링크를 받아 직접 건넨다.
 *
 * <ul>
 *   <li>토큰은 무작위 32 바이트다. 만들 때 한 번만 돌려주고 저장소에는 SHA-256 해시만 둔다
 *   <li>초대는 그 이메일로 가입한 사용자만 받을 수 있다. 링크가 다른 사람에게 넘어가도 쓸 수 없다
 *   <li>7일 뒤 만료된다. 한 번 받으면 다시 쓸 수 없다
 *   <li>이미 멤버면 역할을 내리지 않는다. GUEST 가 MEMBER 초대를 받으면 MEMBER 로 올린다
 * </ul>
 */
public final class InviteService {

    static final Duration TTL = Duration.ofDays(7);

    /** 초대를 만든 결과. token 은 이때만 알 수 있다. */
    public record Created(Invite invite, String token) {
    }

    /** 초대 링크를 연 사람에게 보일 내용. */
    public record Preview(Invite invite, Workspace workspace) {
    }

    private final InviteRepository invites;
    private final WorkspaceRepository workspaces;
    private final UserDirectory users;
    private final PageGrants grants;
    private final Transactions transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InviteService(
            InviteRepository invites,
            WorkspaceRepository workspaces,
            UserDirectory users,
            PageGrants grants,
            Transactions transactions,
            Clock clock) {
        this.invites = invites;
        this.workspaces = workspaces;
        this.users = users;
        this.grants = grants;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Created create(UUID userId, UUID workspaceId, String email, WorkspaceRole role) {
        requireOwner(userId, workspaceId);
        if (role == WorkspaceRole.OWNER) {
            throw new WorkspaceException(Kind.BAD_REQUEST, "OWNER 로는 초대할 수 없다");
        }
        Instant now = now();
        Invite invite = new Invite(UUID.randomUUID(), workspaceId, AuthService.normalizeEmail(email), role, userId, now,
                now.plus(TTL), null);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        invites.create(invite, hash(token));
        return new Created(invite, token);
    }

    public List<Invite> pending(UUID userId, UUID workspaceId) {
        requireOwner(userId, workspaceId);
        return invites.pending(workspaceId, now());
    }

    public void revoke(UUID userId, UUID workspaceId, UUID inviteId) {
        requireOwner(userId, workspaceId);
        if (!invites.revoke(workspaceId, inviteId)) {
            throw new WorkspaceException(Kind.NOT_FOUND, "대기 중인 초대가 없다");
        }
    }

    /** 링크를 연 사람에게 무엇에 초대받았는지 보인다. 토큰을 가진 사람만 볼 수 있다. */
    public Preview preview(String token) {
        Invite invite = pending(token);
        Workspace workspace = workspaces.findWorkspace(invite.workspaceId())
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "초대가 없거나 만료되었다"));
        return new Preview(invite, workspace);
    }

    /**
     * 초대를 받는다. 로그인한 사용자의 이메일이 초대한 이메일과 같아야 한다.
     *
     * @return 들어간 워크스페이스
     * @throws WorkspaceException NOT_FOUND 없거나 만료되었거나 취소됨, CONFLICT 이미 받음, FORBIDDEN 다른 이메일
     */
    public UUID accept(UUID userId, String token) {
        Invite invite = invites.findByTokenHash(hash(token))
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "초대가 없거나 만료되었다"));
        if (invite.acceptedAt() != null) {
            throw new WorkspaceException(Kind.CONFLICT, "이미 받은 초대다");
        }
        if (!invite.pendingAt(now())) {
            throw new WorkspaceException(Kind.NOT_FOUND, "초대가 없거나 만료되었다");
        }
        User user = users.findAllById(List.of(userId)).stream().findFirst()
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "사용자가 없다"));
        if (!user.email().equals(invite.email())) {
            throw new WorkspaceException(Kind.FORBIDDEN, "이 초대는 다른 이메일로 보낸 것이다");
        }
        // 받았다는 표시와 멤버 변경을 한 트랜잭션에서 한다. 따로 하면 멤버 변경이 실패했을 때 초대만 쓰인다. 두 번 눌러도
        // 먼저 표시한 쪽만 멤버를 바꾼다.
        boolean[] changed = {false};
        transactions.run(() -> {
            if (!invites.markAccepted(invite.id(), userId, now())) {
                throw new WorkspaceException(Kind.CONFLICT, "이미 받은 초대다");
            }
            WorkspaceRole current = workspaces.roleOf(invite.workspaceId(), userId).orElse(null);
            if (current == null) {
                changed[0] = workspaces.addMember(invite.workspaceId(), userId, invite.role());
            } else if (current == WorkspaceRole.GUEST && invite.role() == WorkspaceRole.MEMBER) {
                workspaces.changeRole(invite.workspaceId(), userId, WorkspaceRole.MEMBER);
                changed[0] = true;
            }
        });
        if (changed[0]) {
            grants.memberChanged(userId);
        }
        return invite.workspaceId();
    }

    private Invite pending(String token) {
        return invites.findByTokenHash(hash(token))
                .filter(invite -> invite.pendingAt(now()))
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "초대가 없거나 만료되었다"));
    }

    private void requireOwner(UUID userId, UUID workspaceId) {
        WorkspaceRole role = workspaces.roleOf(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "워크스페이스가 없다"));
        if (!role.managesMembers()) {
            throw new WorkspaceException(Kind.FORBIDDEN, "초대는 OWNER 만 할 수 있다");
        }
    }

    /** 만료된 초대를 지운다 (W-4, D-47). 주기 작업이 부른다. */
    public int purgeExpired() {
        return invites.purgeExpired(now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
