package com.copage.collab.app;

import java.util.UUID;

/**
 * 편집과 상관없이 문서를 연 사람들에게 알릴 일 (D-46). 그 문서를 맡은 노드가 어디든 닿게 구현이 노드 사이로 전한다.
 */
public interface DocEvents {

    /** 댓글 스레드가 생기거나 바뀌었다. 연결들은 comment 메시지를 받고 목록을 다시 읽는다. */
    void commentChanged(UUID docId, UUID threadId);
}
