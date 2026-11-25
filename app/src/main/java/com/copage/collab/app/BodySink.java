package com.copage.collab.app;

import java.util.Set;
import java.util.UUID;

/**
 * 검색에 쓸 본문을 알린다 (docs/08-FEATURES.md 10.5, F-1, D-47). 세션이 편집을 커밋하면 길어도 몇 초 안에, 그리고 세션을
 * 올리거나 내릴 때 background 에서 부른다. 받는 쪽은 리비전이 더 큰 것만 쓴다.
 */
public interface BodySink {

    BodySink NONE = (docId, body, rev) -> { };

    /**
     * 본문이 바뀌었다. 세션이 마지막으로 알린 것과 같으면 부르지 않는다 (D-51).
     *
     * @param body 제목을 뺀 모든 블록의 글을 화면 순서로 줄바꿈으로 이은 것
     */
    void update(UUID docId, String body, long rev);

    /**
     * 보이는 글자에 붙은 comment 서식의 값(스레드 ID)들이 세션이 마지막으로 알린 것과 달라졌다(처음에는 빈 것과 비교한다). 여기
     * 없는 스레드는 원문이 지워졌다 (D-50, D-51).
     */
    default void commentThreads(UUID docId, Set<String> threads) {
    }

    /**
     * 방금 커밋한 편집이 이 스레드들의 comment 서식을 붙였다 (D-50). 본문을 다 훑는 update 를 기다리지 않고 바로 알린다. 붙이자마자
     * 지워도 "한때 있었다" 가 남아야 원문이 지워졌다고 보일 수 있다.
     */
    default void commentMarked(UUID docId, Set<String> commentThreads) {
    }
}
