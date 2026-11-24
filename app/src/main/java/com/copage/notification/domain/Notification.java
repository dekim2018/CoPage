package com.copage.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 알림 하나 (docs/08-FEATURES.md 10.3, C-4, D-46).
 *
 * @param docId 알림이 가리키는 페이지. 누르면 그리로 간다
 * @param preview 목록에 보일 짧은 글(멘션이 들어간 문장, 댓글 앞부분)
 * @param readAt 읽었으면 그 시각
 */
public record Notification(
        UUID id,
        UUID userId,
        Kind kind,
        UUID docId,
        UUID actorId,
        String actorName,
        String preview,
        Instant createdAt,
        Instant readAt) {

    public enum Kind {
        /** 본문에서 나를 멘션했다. */
        MENTION,
        /** 댓글에서 나를 멘션했다. */
        COMMENT_MENTION,
        /** 내가 참여한 댓글 스레드에 답글이 달렸다. */
        REPLY
    }
}
