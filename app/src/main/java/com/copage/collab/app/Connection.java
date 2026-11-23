package com.copage.collab.app;

import com.copage.collab.domain.ServerMessage;
import java.util.UUID;

/**
 * 문서 세션이 보는 연결 하나. WebSocket 을 몰라도 되게 감쌌다. 테스트는 가짜 연결을 쓴다.
 *
 * <p>send 는 막히지 않아야 한다. 느린 연결 하나가 세션 전체를 멈추게 두지 않는다 (docs/05-PROTOCOL.md 7.7).
 */
public interface Connection {

    String id();

    UUID userId();

    void send(ServerMessage message);

    void close(int code, String reason);
}
