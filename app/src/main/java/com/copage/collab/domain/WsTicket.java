package com.copage.collab.domain;

import java.util.UUID;

/**
 * 1회용 WebSocket 티켓에 담긴 값 (docs/07-SECURITY.md 9.3). 이 사용자가 이 문서를 이 등급으로 열 수 있다는 뜻이다.
 *
 * @param name 접속자 목록과 남의 커서에 보일 이름 (docs/05-PROTOCOL.md 7.6)
 * @param shareLinkId 공유 링크로 받은 티켓이면 그 링크 ID, 아니면 null (D-41)
 * @param color 프로필에서 고른 커서 색. 없으면 null 이고 사용자 ID 로 정한 색을 쓴다 (A-3)
 */
public record WsTicket(UUID userId, UUID docId, PageRole role, String name, UUID shareLinkId, String color) {

    public WsTicket(UUID userId, UUID docId, PageRole role, String name, UUID shareLinkId) {
        this(userId, docId, role, name, shareLinkId, null);
    }

    public WsTicket(UUID userId, UUID docId, PageRole role, String name) {
        this(userId, docId, role, name, null, null);
    }
}
