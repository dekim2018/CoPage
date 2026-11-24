package com.copage.workspace.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 워크스페이스 초대. 토큰은 들고 있지 않는다(저장소에는 해시만 있다).
 *
 * @param email 초대한 이메일. 정규화한 값이다
 * @param acceptedAt 받았으면 그 시각, 아니면 null
 */
public record Invite(
        UUID id,
        UUID workspaceId,
        String email,
        WorkspaceRole role,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant acceptedAt) {

    public boolean pendingAt(Instant now) {
        return acceptedAt == null && now.isBefore(expiresAt);
    }
}
