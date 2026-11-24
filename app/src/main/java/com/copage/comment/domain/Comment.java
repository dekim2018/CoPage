package com.copage.comment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 댓글 하나(스레드의 첫 글이나 답글).
 *
 * @param editedAt 마지막으로 고친 때. 고치지 않았으면 null
 * @param deleted 지웠는지. 지운 댓글은 글이 비어 있다 (D-49)
 */
public record Comment(UUID id, UUID threadId, UUID authorId, String authorName, String body, Instant createdAt,
        Instant editedAt, boolean deleted) {

    public Comment(UUID id, UUID threadId, UUID authorId, String authorName, String body, Instant createdAt) {
        this(id, threadId, authorId, authorName, body, createdAt, null, false);
    }
}
