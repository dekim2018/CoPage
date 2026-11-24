package com.copage.collab.app;

import java.util.UUID;

/**
 * 본문 멘션을 알린다 (docs/08-FEATURES.md 10.3, C-4, D-46). 세션이 묶음을 커밋한 뒤, 새로 넣은 글자 중 mention 서식이 붙은
 * 것마다 background 에서 부른다. 받는 쪽은 멘션된 사람이 그 페이지를 볼 수 있을 때만 알린다.
 */
public interface MentionSink {

    MentionSink NONE = (docId, actorId, mentionId, mentionedUserId, preview) -> { };

    /**
     * @param mentionId 클라이언트가 멘션마다 만든 ID. 되돌리기나 복원으로 같은 멘션이 다시 들어와도 알림은 한 번이다
     * @param preview 멘션이 들어간 블록의 글
     */
    void mentioned(UUID docId, UUID actorId, String mentionId, UUID mentionedUserId, String preview);
}
