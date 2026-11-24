package com.copage.collab.domain;

import java.util.UUID;

/**
 * 1회용 WebSocket 티켓에 담긴 값 (docs/07-SECURITY.md 9.3). 이 사용자가 이 문서를 이 등급으로 열 수 있다는 뜻이다.
 *
 * @param name 접속자 목록과 남의 커서에 보일 이름 (docs/05-PROTOCOL.md 7.6)
 * @param shareLinkId 공유 링크로 받은 티켓이면 그 링크 ID, 아니면 null (D-41)
 */
public record WsTicket(UUID userId, UUID docId, PageRole role, String name, UUID shareLinkId) {

    public WsTicket(UUID userId, UUID docId, PageRole role, String name) {
        this(userId, docId, role, name, null);
    }
}
