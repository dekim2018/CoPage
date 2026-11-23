package com.copage.collab.app;

import java.util.UUID;

/**
 * 문서 제목(루트 블록의 글)이 바뀌면 알리는 포트 (D-21). 페이지 목록과 경로가 이 제목을 보인다. workspace 모듈이 받아 저장한다.
 *
 * <p>세션 실행 흐름 밖에서 부르므로 여러 번의 알림이 순서 없이 도착할 수 있다. 받는 쪽은 rev 가 더 클 때만 바꾼다.
 */
public interface TitleSink {

    /** @param rev 이 제목을 읽은 문서 리비전 */
    void update(UUID docId, String title, long rev);
}
