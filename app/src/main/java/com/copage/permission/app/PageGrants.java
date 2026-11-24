package com.copage.permission.app;

import com.copage.identity.app.UserDirectory;
import com.copage.identity.domain.User;
import com.copage.permission.app.GrantRepository.GrantRow;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.app.PermissionException.Kind;
import com.copage.permission.app.PermissionRepository.Chain;
import com.copage.permission.app.PermissionRepository.ChainRow;
import com.copage.permission.domain.PageLevel;
import com.copage.permission.domain.PermissionRules.Role;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 페이지 권한 부여, 회수, restricted (docs/09-API.md 11.3, docs/07-SECURITY.md 9.4). 모두 그 페이지의 FULL 이 필요하다.
 *
 * <p>바꾼 뒤에는 영향받는 범위(그 페이지와 하위, 그 사용자)를 {@link PermissionChange} 로 알린다 (D-17).
 */
public final class PageGrants {

    /**
     * 권한 목록 한 줄.
     *
     * @param inheritedFrom 조상 페이지에서 물려받은 것이면 그 페이지 ID. 이 페이지에 준 것이면 null
     */
    public record GrantView(UUID userId, String email, String name, PageLevel level, UUID inheritedFrom) {
    }

    private final PagePermissions permissions;
    private final PermissionRepository chains;
    private final GrantRepository grants;
    private final WorkspaceMembership membership;
    private final UserDirectory users;
    private final Consumer<PermissionChange> publisher;
    private volatile GrantNotices notices = GrantNotices.NONE;

    public PageGrants(
            PagePermissions permissions,
            PermissionRepository chains,
            GrantRepository grants,
            WorkspaceMembership membership,
            UserDirectory users,
            Consumer<PermissionChange> publisher) {
        this.permissions = permissions;
        this.chains = chains;
        this.grants = grants;
        this.membership = membership;
        this.users = users;
        this.publisher = publisher;
    }

    /** 이 페이지에 준 권한과, 경계(restricted)까지의 조상에서 물려받는 권한. */
    public List<GrantView> list(UUID actor, UUID pageId) {
        requireFull(actor, pageId);
        Chain chain = chains.chain(actor, pageId);
        List<UUID> scope = new ArrayList<>();
        for (ChainRow row : chain.rows()) {
            scope.add(row.pageId());
            if (row.restricted()) {
                break;
            }
        }
        List<GrantRow> rows = grants.grantsOn(scope);
        Map<UUID, User> byId = users.findAllById(rows.stream().map(GrantRow::userId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return rows.stream()
                .filter(row -> byId.containsKey(row.userId()))
                .sorted(Comparator.comparingInt((GrantRow row) -> scope.indexOf(row.pageId()))
                        .thenComparing(row -> byId.get(row.userId()).email()))
                .map(row -> {
                    User user = byId.get(row.userId());
                    return new GrantView(user.id(), user.email(), user.name(), row.level(),
                            row.pageId().equals(pageId) ? null : row.pageId());
                })
                .toList();
    }

    /** 가입한 사용자에게 준다. 워크스페이스에 없으면 GUEST 로 넣는다 (D-13). 이미 있으면 등급을 바꾼다. */
    public GrantView grant(UUID actor, UUID pageId, UUID userId, PageLevel level) {
        User user = users.findAllById(List.of(userId)).stream().findFirst()
                .orElseThrow(() -> new PermissionException(Kind.NOT_FOUND, "사용자가 없다"));
        return grant(actor, pageId, user, level);
    }

    public GrantView grantByEmail(UUID actor, UUID pageId, String email, PageLevel level) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new PermissionException(Kind.NOT_FOUND, "그 이메일로 가입한 사용자가 없다"));
        return grant(actor, pageId, user, level);
    }

    private GrantView grant(UUID actor, UUID pageId, User user, PageLevel level) {
        Access.Granted page = requireFull(actor, pageId);
        membership.ensureGuest(page.workspaceId(), user.id());
        grants.upsert(pageId, user.id(), level, actor);
        publisher.accept(new PermissionChange(grants.subtree(pageId), user.id()));
        notices.granted(actor, pageId, user.id(), level);
        return new GrantView(user.id(), user.email(), user.name(), level, null);
    }

    /** 권한을 준 것을 받을 곳 (D-50). */
    public void grantNoticesTo(GrantNotices notices) {
        this.notices = notices;
    }

    /** 이 페이지에 준 권한만 지운다. 조상에서 물려받은 권한은 그 조상에서 지워야 한다. */
    public void revoke(UUID actor, UUID pageId, UUID userId) {
        requireFull(actor, pageId);
        if (!grants.delete(pageId, userId)) {
            throw new PermissionException(Kind.NOT_FOUND, "이 페이지에 그 사용자에게 준 권한이 없다");
        }
        publisher.accept(new PermissionChange(grants.subtree(pageId), userId));
    }

    /**
     * 상속을 끊거나 다시 잇는다. 끊는 사람이 OWNER 가 아니면 그 페이지에 자신의 FULL 을 남긴다 (D-14). 조상에서 물려받은 FULL
     * 이 경계에 막혀 자기 자신이 잠기지 않게 하기 위해서다.
     */
    public void setRestricted(UUID actor, UUID pageId, boolean restricted) {
        requireFull(actor, pageId);
        if (restricted && chains.chain(actor, pageId).role() != Role.OWNER) {
            grants.upsert(pageId, actor, PageLevel.FULL, actor);
        }
        grants.setRestricted(pageId, restricted);
        publisher.accept(new PermissionChange(grants.subtree(pageId), null));
    }

    /** 워크스페이스에서 내보낸 사용자의 권한을 지운다 (D-16). workspace 모듈이 내보낸 뒤에 부른다. */
    public void removeAllInWorkspace(UUID workspaceId, UUID userId) {
        grants.deleteAllInWorkspace(workspaceId, userId);
    }

    /**
     * 워크스페이스 역할이 바뀌거나 내보냈을 때 그 사용자의 모든 페이지를 다시 계산하게 알린다. 다른 워크스페이스의 페이지도 범위에
     * 들지만, 받는 쪽은 그 사용자의 열린 연결만 다시 계산하므로 결과가 바뀌지 않는다.
     */
    public void memberChanged(UUID userId) {
        publisher.accept(PermissionChange.allPagesOf(userId));
    }

    /**
     * 페이지 트리가 바뀌었을 때(옮기기, 휴지통으로 보내기와 꺼내기) 그 페이지들에 접근하는 모든 사람의 등급을 다시 계산하게 알린다.
     * 옮기면 물려받는 권한과 경계가 바뀌고, 휴지통에 가면 아무도 볼 수 없다.
     *
     * @param pageIds 옮기거나 보내거나 꺼낸 페이지와 그 하위 전부
     */
    public void treeChanged(Set<UUID> pageIds) {
        publisher.accept(new PermissionChange(pageIds, null));
    }

    private Access.Granted requireFull(UUID actor, UUID pageId) {
        return switch (permissions.access(actor, pageId)) {
            case Access.NotFound notFound -> throw new PermissionException(Kind.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new PermissionException(Kind.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> {
                if (!granted.level().atLeast(PageLevel.FULL)) {
                    throw new PermissionException(Kind.FORBIDDEN, "공유 설정은 전체 권한이 있어야 바꿀 수 있다");
                }
                yield granted;
            }
        };
    }
}
