package com.copage.permission.app;

import com.copage.permission.domain.PageLevel;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** page_grants 와 pages.restricted 를 바꾸는 포트. */
public interface GrantRepository {

    record GrantRow(UUID pageId, UUID userId, PageLevel level) {
    }

    List<GrantRow> grantsOn(Collection<UUID> pageIds);

    void upsert(UUID pageId, UUID userId, PageLevel level, UUID grantedBy);

    /** @return 지운 것이 있었는지 */
    boolean delete(UUID pageId, UUID userId);

    void setRestricted(UUID pageId, boolean restricted);

    /** 페이지 자신과 모든 하위 페이지(휴지통 포함). */
    Set<UUID> subtree(UUID pageId);

    /** 그 워크스페이스의 페이지에 그 사용자에게 준 권한을 모두 지운다. */
    int deleteAllInWorkspace(UUID workspaceId, UUID userId);
}
