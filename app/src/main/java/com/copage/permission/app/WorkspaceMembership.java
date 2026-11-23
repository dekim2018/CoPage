package com.copage.permission.app;

import java.util.UUID;

/**
 * 페이지 권한을 줄 때 워크스페이스 멤버가 아닌 사용자를 GUEST 로 넣는 포트 (docs/review/DECISIONS.md D-13). 멤버 목록은
 * workspace 모듈의 것이라 그쪽이 구현한다.
 */
public interface WorkspaceMembership {

    /** 이미 멤버면 역할을 바꾸지 않는다. */
    void ensureGuest(UUID workspaceId, UUID userId);
}
