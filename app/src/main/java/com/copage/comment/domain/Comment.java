package com.copage.comment.domain;

import java.time.Instant;
import java.util.UUID;

/** 댓글 하나(스레드의 첫 글이나 답글). */
public record Comment(UUID id, UUID threadId, UUID authorId, String authorName, String body, Instant createdAt) {
}
