package com.copage.workspace.app;

import java.util.Set;
import java.util.UUID;

/**
 * 페이지 트리가 바뀌었다고 그 워크스페이스를 보는 사이드바들에 알린다 (W-5, docs/08-FEATURES.md 10.4, D-46). 사이드바는 받은
 * 부모들의 목록을 다시 읽는다.
 */
public interface TreeEvents {

    TreeEvents NONE = (workspaceId, parentIds) -> { };

    /** @param parentIds 하위 목록이 바뀐 부모. 최상위는 null 대신 워크스페이스 ID 로 적는다 */
    void changed(UUID workspaceId, Set<UUID> parentIds);
}
