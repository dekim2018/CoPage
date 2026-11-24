package com.copage.collab.app;

import java.util.UUID;

/**
 * 검색에 쓸 본문을 알린다 (docs/08-FEATURES.md 10.5, F-1, D-47). 세션이 편집을 커밋하면 길어도 몇 초 안에, 그리고 세션을
 * 올리거나 내릴 때 background 에서 부른다. 받는 쪽은 리비전이 더 큰 것만 쓴다.
 */
public interface BodySink {

    BodySink NONE = (docId, body, rev) -> { };

    /** @param body 제목을 뺀 모든 블록의 글을 화면 순서로 줄바꿈으로 이은 것 */
    void update(UUID docId, String body, long rev);
}
