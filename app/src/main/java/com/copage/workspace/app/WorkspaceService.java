package com.copage.workspace.app;

import com.copage.identity.app.UserDirectory;
import com.copage.identity.domain.User;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.domain.PageLevel;
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
 * <p>워크스페이스에 속하지 않은 사용자에게는 워크스페이스가 있는지도 알리지 않는다(NOT_FOUND). 페이지 접근은 permission
 * 모듈이 계산한 등급(docs/07-SECURITY.md 9.4)으로 판단한다. 목록에서는 VIEW 가 없는 페이지를 빼고, 페이지를 만들려면 부모의
 * EDIT(최상위면 역할의 기본 권한)가 있어야 한다.
 */
public final class WorkspaceService {

    /** 멤버 목록 한 줄. */
    public record MemberView(UUID userId, String email, String name, WorkspaceRole role) {
    }

    /** 목록의 한 페이지와 내 등급. */
    public record ChildView(PageSummary summary, PageLevel level) {
    }

    /**
     * 한 페이지와 내 등급, 경로.
     *
     * @param path 내가 볼 수 있는 조상. 루트 쪽부터
     */
    public record PageView(Page page, PageLevel level, List<Page> path) {
    }

    private final WorkspaceRepository repository;
    private final UserDirectory users;
    private final PagePermissions permissions;
    private final PageGrants grants;
    private final Transactions transactions;
    private final Clock clock;

    public WorkspaceService(
            WorkspaceRepository repository,
            UserDirectory users,
            PagePermissions permissions,
            PageGrants grants,
            Transactions transactions,
            Clock clock) {
        this.repository = repository;
        this.users = users;
        this.permissions = permissions;
        this.grants = grants;
        this.transactions = transactions;
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

    /**
     * 멤버의 역할을 바꾼다. OWNER 만 할 수 있다. 마지막 OWNER 는 내릴 수 없다 (D-15). 바꾼 뒤 그 사람의 열린 연결이 등급을 다시
     * 계산하게 알린다 (D-17).
     */
    public void changeRole(UUID userId, UUID workspaceId, UUID memberId, WorkspaceRole role) {
        requireOwner(workspaceId, userId);
        apply(repository.changeRole(workspaceId, memberId, role));
        grants.memberChanged(memberId);
    }

    /**
     * 멤버를 내보낸다. OWNER 만 할 수 있다. 그 워크스페이스에서 받은 페이지 권한도 같은 트랜잭션에서 지운다 (D-16). 따로 커밋하면
     * 권한 삭제가 실패했을 때 멤버만 빠지고 권한이 남아, 다시 넣으면 옛 권한이 되살아난다.
     */
    public void removeMember(UUID userId, UUID workspaceId, UUID memberId) {
        requireOwner(workspaceId, userId);
        transactions.run(() -> {
            apply(repository.removeMember(workspaceId, memberId));
            grants.removeAllInWorkspace(workspaceId, memberId);
        });
        grants.memberChanged(memberId);
    }

    private void requireOwner(UUID workspaceId, UUID userId) {
        if (!roleOf(workspaceId, userId).managesMembers()) {
            throw new WorkspaceException(Kind.FORBIDDEN, "멤버 관리는 OWNER 만 할 수 있다");
        }
    }

    private static void apply(WorkspaceRepository.MemberUpdate result) {
        switch (result) {
            case UPDATED -> {
            }
            case NOT_MEMBER -> throw new WorkspaceException(Kind.NOT_FOUND, "그 사용자는 이 워크스페이스의 멤버가 아니다");
            case LAST_OWNER -> throw new WorkspaceException(Kind.CONFLICT, "마지막 OWNER 는 내리거나 내보낼 수 없다");
        }
    }

    /** 목록에 보일 제목의 최대 길이(UTF-16 코드 단위). 루트 블록 글이 더 길면 자른다. */
    static final int MAX_TITLE_LENGTH = 200;

    /**
     * 문서 세션이 루트 블록의 글을 알린다 (D-21). 권한 검사 없이 서버 안에서만 부른다. 페이지가 없는 문서면 아무것도 하지 않는다.
     * 늦게 도착한 알림이 새 제목을 덮지 않도록 rev 가 더 클 때만 바꾼다.
     */
    public void updateTitle(UUID pageId, String title, long rev) {
        String trimmed = title.strip();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            int end = MAX_TITLE_LENGTH;
            // 서로게이트 쌍의 가운데를 자르지 않는다.
            if (Character.isHighSurrogate(trimmed.charAt(end - 1))) {
                end--;
            }
            trimmed = trimmed.substring(0, end);
        }
        repository.updateTitle(pageId, trimmed, rev);
    }

    /** parentId 의 한 단계 하위 페이지. parentId 가 null 이면 최상위. GUEST 는 지금 빈 목록이다. */
    public List<ChildView> children(UUID userId, UUID workspaceId, UUID parentId) {
        roleOf(workspaceId, userId);
        if (parentId != null && !(permissions.access(userId, parentId) instanceof Access.Granted granted
                && granted.workspaceId().equals(workspaceId))) {
            throw new WorkspaceException(Kind.NOT_FOUND, "페이지가 없다");
        }
        List<PageSummary> children = repository.children(workspaceId, parentId);
        Map<UUID, PageLevel> levels = permissions.childLevels(
                userId, workspaceId, parentId, children.stream().map(child -> child.page().id()).toList());
        return children.stream()
                .filter(child -> levels.containsKey(child.page().id()))
                .map(child -> new ChildView(child, levels.get(child.page().id())))
                .toList();
    }

    /** 페이지 하나. 없거나 볼 수 없으면 NOT_FOUND, 워크스페이스에 속하지만 등급이 없으면 FORBIDDEN. */
    public PageView page(UUID userId, UUID pageId) {
        return switch (permissions.access(userId, pageId)) {
            case Access.NotFound notFound -> throw new WorkspaceException(Kind.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new WorkspaceException(Kind.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> {
                Page page = repository.findPage(granted.workspaceId(), pageId)
                        .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "페이지가 없다"));
                Map<UUID, Page> ancestors = repository.findPages(granted.workspaceId(), granted.visibleAncestors()).stream()
                        .collect(Collectors.toMap(Page::id, Function.identity()));
                List<Page> path = granted.visibleAncestors().stream()
                        .filter(ancestors::containsKey)
                        .map(ancestors::get)
                        .toList();
                yield new PageView(page, granted.level(), path);
            }
        };
    }

    /**
     * 페이지를 만든다. 문서는 처음 열 때 빈 문서로 생긴다.
     *
     * @param parentId 최상위면 null
     * @param afterId 이 형제 바로 뒤에 둔다. null 이면 맨 뒤
     */
    public Page createPage(UUID userId, UUID workspaceId, UUID parentId, UUID afterId) {
        roleOf(workspaceId, userId);
        PageLevel level = permissions.levelForNewChild(userId, workspaceId, parentId);
        if (level == null && parentId != null) {
            throw new WorkspaceException(Kind.NOT_FOUND, "부모 페이지가 없다");
        }
        if (level == null || !level.atLeast(PageLevel.EDIT)) {
            throw new WorkspaceException(Kind.FORBIDDEN, parentId == null
                    ? "이 워크스페이스의 최상위에 페이지를 만들 수 없다"
                    : "부모 페이지에 편집 권한이 없다");
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
