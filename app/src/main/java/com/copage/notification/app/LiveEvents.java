package com.copage.notification.app;

import java.util.Map;
import java.util.UUID;

/**
 * 사용자의 열린 알림 연결(SSE)에 실시간으로 보낸다. 그 연결이 다른 노드에 있을 수 있어 구현이 노드 사이로 전한다 (5.5 의
 * user:{userId}:notify). 받는 사람이 연결되어 있지 않으면 버린다(저장되는 알림은 다음에 목록으로 받는다).
 */
public interface LiveEvents {

    /** @param type 화면이 고를 이벤트 이름(notification, tree) */
    void send(UUID userId, String type, Map<String, Object> data);
}
