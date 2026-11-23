package com.copage.permission.app;

import java.util.Set;
import java.util.UUID;

/**
 * 권한이 바뀌어 다시 계산해야 하는 범위. 커밋한 뒤에 알린다.
 *
 * @param pageIds 등급이 바뀌었을 수 있는 페이지. 바뀐 페이지와 그 하위 전부. null 이면 모든 페이지다. 워크스페이스 역할이
 *     바뀐 경우에 쓴다. 그때는 userId 가 범위를 충분히 좁히므로 페이지 ID 를 모두 싣지 않는다
 * @param userId 등급이 바뀌었을 수 있는 사용자. null 이면 그 페이지에 접근하는 모든 사용자
 */
public record PermissionChange(Set<UUID> pageIds, UUID userId) {

    public PermissionChange {
        if (pageIds == null && userId == null) {
            throw new IllegalArgumentException("페이지와 사용자 중 하나는 좁혀야 한다");
        }
        pageIds = pageIds == null ? null : Set.copyOf(pageIds);
    }

    /** 그 사용자의 모든 페이지. */
    public static PermissionChange allPagesOf(UUID userId) {
        return new PermissionChange(null, userId);
    }

    public boolean affects(UUID pageId, UUID user) {
        return (pageIds == null || pageIds.contains(pageId)) && (userId == null || userId.equals(user));
    }
}
