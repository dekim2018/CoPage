package com.copage.workspace.domain;

import java.time.Instant;
import java.util.UUID;

public record Workspace(UUID id, String name, UUID ownerId, Instant createdAt) {
}
