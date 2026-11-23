package com.copage.collab.domain;

import java.util.UUID;

/** 1회용 WebSocket 티켓에 담긴 값 (docs/07-SECURITY.md 9.3). 이 사용자가 이 문서를 이 등급으로 열 수 있다는 뜻이다. */
public record WsTicket(UUID userId, UUID docId, PageRole role) {
}
