package com.copage.workspace.app;

import com.copage.identity.app.UserDirectory;
import com.copage.identity.domain.User;
import com.copage.workspace.app.WorkspaceException.Kind;
import com.copage.workspace.app.WorkspaceRepository.Member;
import com.copage.workspace.app.WorkspaceRepository.Membership;
import com.copage.workspace.app.WorkspaceRepository.PageSummary;
import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 워크스페이스, 멤버, 페이지 트리 (docs/09-API.md 11.2).
 *
 * <p>워크스페이스에 속하지 않은 사용자에게는 워크스페이스가 있는지도 알리지 않는다(NOT_FOUND). 접근은 지금 워크스페이스 역할로만
 * 판단한다. OWNER, MEMBER 는 모든 페이지를 보고 만들 수 있고, GUEST 는 아무 페이지도 보지 못한다. 페이지 권한을 받은 GUEST 와
 * restricted 페이지는 permission 모듈이 생기면서 바뀐다 (docs/07-SECURITY.md 9.4).
 */
public final class WorkspaceService {

    /** 멤버 목록 한 줄. */
    public record MemberView(UUID userId, String email, String name, WorkspaceRole role) {
    }

    private final WorkspaceRepository repository;
    private final UserDirectory users;
    private final Clock clock;

    public WorkspaceService(WorkspaceRepository repository, UserDirectory users, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.clock = clock;
    }

    public List<Membership> list(UUID userId) {
        return repository.findByMember(userId);
    }

    public Membership create(UUID userId, String name) {
        Workspace workspace = new Workspace(UUID.randomUUID(), name.trim(), userId, now());
        repository.create(workspace);
        return new Membership(workspace, WorkspaceRole.OWNER);
    }

    public List<MemberView> members(UUID userId, UUID workspaceId) {
        WorkspaceRole role = roleOf(workspaceId, userId);
        if (!role.seesAllPages()) {
            throw new WorkspaceException(Kind.FORBIDDEN, "게스트는 멤버 목록을 볼 수 없다");
        }
        List<Member> members = repository.members(workspaceId);
        Map<UUID, User> byId = users.findAllById(members.stream().map(Member::userId).toList()).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return members.stream()
                .filter(member -> byId.containsKey(member.userId()))
                .map(member -> {
                    User user = byId.get(member.userId());
                    return new MemberView(user.id(), user.email(), user.name(), member.role());
                })
                .sorted(Comparator.comparing(MemberView::role).thenComparing(MemberView::email))
                .toList();
    }

    /**
     * 가입한 사용자를 이메일로 바로 넣는다. 초대 메일과 수락(docs/09-API.md 11.2 의 invites)은 나중에 이 자리를 대신한다.
     *
     * @param role MEMBER 또는 GUEST. OWNER 는 넣을 수 없다
     */
    public MemberView addMember(UUID userId, UUID workspaceId, String email, WorkspaceRole role) {
        if (!roleOf(workspaceId, userId).managesMembers()) {
            throw new WorkspaceException(Kind.FORBIDDEN, "멤버는 OWNER 만 넣을 수 있다");
        }
        if (role == WorkspaceRole.OWNER) {
            throw new WorkspaceException(Kind.BAD_REQUEST, "OWNER 로는 넣을 수 없다");
        }
        User user = users.findByEmail(email)
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "그 이메일로 가입한 사용자가 없다"));
        if (!repository.addMember(workspaceId, user.id(), role)) {
            throw new WorkspaceException(Kind.CONFLICT, "이미 이 워크스페이스의 멤버다");
        }
        return new MemberView(user.id(), user.email(), user.name(), role);
    }

    /** parentId 의 한 단계 하위 페이지. parentId 가 null 이면 최상위. GUEST 는 지금 빈 목록이다. */
    public List<PageSummary> children(UUID userId, UUID workspaceId, UUID parentId) {
        WorkspaceRole role = roleOf(workspaceId, userId);
        if (!role.seesAllPages()) {
            return List.of();
        }
        if (parentId != null && repository.findPage(workspaceId, parentId).isEmpty()) {
            throw new WorkspaceException(Kind.NOT_FOUND, "페이지가 없다");
        }
        return repository.children(workspaceId, parentId);
    }

    /**
     * 페이지를 만든다. 문서는 처음 열 때 빈 문서로 생긴다.
     *
     * @param parentId 최상위면 null
     * @param afterId 이 형제 바로 뒤에 둔다. null 이면 맨 뒤
     */
    public Page createPage(UUID userId, UUID workspaceId, UUID parentId, UUID afterId) {
        WorkspaceRole role = roleOf(workspaceId, userId);
        if (!role.seesAllPages()) {
            throw new WorkspaceException(Kind.FORBIDDEN, "게스트는 페이지를 만들 수 없다");
        }
        return repository.createPage(workspaceId, UUID.randomUUID(), parentId, afterId, userId, now());
    }

    private WorkspaceRole roleOf(UUID workspaceId, UUID userId) {
        return repository.roleOf(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "워크스페이스가 없다"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
