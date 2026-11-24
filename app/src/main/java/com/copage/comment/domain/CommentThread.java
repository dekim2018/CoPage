package com.copage.comment.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 댓글 스레드 (docs/08-FEATURES.md 10.2, C-1~C-3, D-46). 본문의 comment 서식이 이 ID 로 달린 글자를 가리킨다.
 *
 * @param quote 달 때의 원문. 본문에서 그 글자가 지워져도 패널에 남는다
 * @param resolvedAt 해결했으면 그 시각
 */
public record CommentThread(
        UUID id,
        UUID docId,
        String quote,
        UUID createdBy,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedByName,
        List<Comment> comments) {
}
