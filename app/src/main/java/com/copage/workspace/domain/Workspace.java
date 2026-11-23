package com.copage.workspace.domain;

import java.time.Instant;
import java.util.UUID;

/** @param ownerId 만든 사람. 권한 판단에는 쓰지 않는다. 역할은 workspace_members 의 행 하나만 본다 */
public record Workspace(UUID id, String name, UUID ownerId, Instant createdAt) {
}
