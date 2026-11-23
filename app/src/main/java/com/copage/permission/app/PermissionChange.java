package com.copage.permission.app;

import java.util.Set;
import java.util.UUID;

/**
 * 권한이 바뀌어 다시 계산해야 하는 범위. 커밋한 뒤에 알린다.
 *
 * @param pageIds 등급이 바뀌었을 수 있는 페이지. 바뀐 페이지와 그 하위 전부
 * @param userId 등급이 바뀌었을 수 있는 사용자. null 이면 그 페이지에 접근하는 모든 사용자
 */
public record PermissionChange(Set<UUID> pageIds, UUID userId) {

    public PermissionChange {
        pageIds = Set.copyOf(pageIds);
    }

    public boolean affects(UUID pageId, UUID user) {
        return pageIds.contains(pageId) && (userId == null || userId.equals(user));
    }
}
