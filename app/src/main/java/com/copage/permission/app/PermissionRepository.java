package com.copage.permission.app;

import com.copage.permission.domain.PageLevel;
import com.copage.permission.domain.PermissionRules.Link;
import com.copage.permission.domain.PermissionRules.Role;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 권한 계산에 필요한 트리와 부여를 읽는 포트. */
public interface PermissionRepository {

    /**
     * 조상 사슬의 한 칸.
     *
     * @param archived 이 페이지가 휴지통에 있는지
     * @param grant 이 페이지에서 그 사용자에게 준 등급. 없으면 null
     */
    record ChainRow(UUID pageId, boolean restricted, boolean archived, PageLevel grant) {
        public Link link() {
            return new Link(restricted, grant);
        }
    }

    /**
     * @param role 그 사용자의 워크스페이스 역할. 속하지 않았으면 null
     * @param rows 페이지 자신부터 루트까지
     */
    record Chain(UUID workspaceId, Role role, List<ChainRow> rows) {
    }

    /** 페이지 자신부터 루트까지의 사슬을 한 번에 읽는다. 페이지가 없으면 null. */
    Chain chain(UUID userId, UUID pageId);

    /** 워크스페이스 역할. 속하지 않았으면 null. */
    Role role(UUID workspaceId, UUID userId);

    /** 주어진 페이지들의 restricted 와 그 사용자에게 준 등급. */
    Map<UUID, Link> links(UUID userId, Collection<UUID> pageIds);
}
