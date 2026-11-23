package com.copage.permission.app;

import com.copage.permission.app.PermissionRepository.Chain;
import com.copage.permission.app.PermissionRepository.ChainRow;
import com.copage.permission.domain.PageLevel;
import com.copage.permission.domain.PermissionRules;
import com.copage.permission.domain.PermissionRules.Link;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 페이지 권한을 계산한다 (docs/07-SECURITY.md 9.4). 다른 모듈은 이 클래스로만 권한을 묻는다.
 *
 * <p>페이지가 없거나, 휴지통에 있거나(조상이 휴지통에 있어도), 사용자가 그 워크스페이스에 속하지 않으면 {@link Access.NotFound}
 * 다. 존재 여부를 흘리지 않기 위해서다. 속해 있지만 등급이 없으면 {@link Access.Denied} 다.
 */
public final class PagePermissions {

    /** 한 페이지에 대한 접근. */
    public sealed interface Access {

        record NotFound() implements Access {
        }

        record Denied(UUID workspaceId) implements Access {
        }

        /** @param visibleAncestors 이 사용자가 볼 수 있는 조상. 루트 쪽부터. 경로 표시에 쓴다 */
        record Granted(UUID workspaceId, PageLevel level, List<UUID> visibleAncestors) implements Access {
        }
    }

    private final PermissionRepository repository;

    public PagePermissions(PermissionRepository repository) {
        this.repository = repository;
    }

    public Access access(UUID userId, UUID pageId) {
        Chain chain = repository.chain(userId, pageId);
        if (chain == null || chain.role() == null || chain.rows().stream().anyMatch(ChainRow::archived)) {
            return new Access.NotFound();
        }
        List<Link> links = chain.rows().stream().map(ChainRow::link).toList();
        PageLevel level = PermissionRules.level(chain.role(), links);
        if (level == null) {
            return new Access.Denied(chain.workspaceId());
        }
        // 조상 하나의 사슬은 이 사슬의 뒷부분이다. 조상마다 다시 읽지 않는다.
        List<UUID> visible = new ArrayList<>();
        for (int i = links.size() - 1; i >= 1; i--) {
            if (PermissionRules.level(chain.role(), links.subList(i, links.size())) != null) {
                visible.add(chain.rows().get(i).pageId());
            }
        }
        return new Access.Granted(chain.workspaceId(), level, List.copyOf(visible));
    }

    /**
     * 한 부모 바로 아래 페이지들의 등급. 볼 수 없는 페이지는 결과에 없다. 부모의 사슬을 한 번 읽고 자식마다 한 칸을 붙여 계산하므로
     * 자식 수만큼 사슬을 다시 읽지 않는다.
     *
     * @param parentId 최상위면 null
     */
    public Map<UUID, PageLevel> childLevels(
            UUID userId, UUID workspaceId, UUID parentId, Collection<UUID> childIds) {
        List<Link> parentLinks;
        PermissionRules.Role role;
        if (parentId == null) {
            role = repository.role(workspaceId, userId);
            parentLinks = List.of();
        } else {
            Chain chain = repository.chain(userId, parentId);
            if (chain == null || !chain.workspaceId().equals(workspaceId)) {
                return Map.of();
            }
            role = chain.role();
            parentLinks = chain.rows().stream().map(ChainRow::link).toList();
        }
        Map<UUID, PageLevel> levels = new HashMap<>();
        repository.links(userId, childIds).forEach((childId, link) -> {
            List<Link> links = new ArrayList<>(parentLinks.size() + 1);
            links.add(link);
            links.addAll(parentLinks);
            PageLevel level = PermissionRules.level(role, links);
            if (level != null) {
                levels.put(childId, level);
            }
        });
        return levels;
    }

    /**
     * 이 부모 아래에 페이지를 만들 수 있는 등급. 최상위면 워크스페이스 역할의 기본 권한이다. 부모가 보이지 않으면 null.
     *
     * @param parentId 최상위면 null
     */
    public PageLevel levelForNewChild(UUID userId, UUID workspaceId, UUID parentId) {
        if (parentId == null) {
            return PermissionRules.rootLevel(repository.role(workspaceId, userId));
        }
        return access(userId, parentId) instanceof Access.Granted granted && granted.workspaceId().equals(workspaceId)
                ? granted.level()
                : null;
    }
}
