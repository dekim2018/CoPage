package com.copage.collab.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.copage.collab.app.ChangePolicy;
import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.DocumentSession;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.WsTicket;
import com.copage.collab.out.InMemoryDocumentStore;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** 연결 단위 빈도 제한. 시계와 실행기를 직접 돌려 시간에 기대지 않는다. */
class CollabWebSocketHandlerTest {

    private static final UUID DOC = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Instant now = Instant.parse("2026-11-24T00:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final DocumentSessions sessions = new DocumentSessions(
            new InMemoryDocumentStore(),
            new ChangePolicy(100, 1000),
            new DocumentSession.Settings(10, 5000, Duration.ofDays(7), Duration.ofSeconds(60), 200, 5000),
            Runnable::run,
            Runnable::run,
            (task, delay) -> { },
            clock,
            (docId, title, rev) -> { });

    private final CollabWebSocketHandler handler = new CollabWebSocketHandler(
            sessions,
            new ProtocolCodec(JsonMapper.builder().build()),
            new CollabWebSocketHandler.Limits(65536, 30, 5, Duration.ofSeconds(45), Duration.ofSeconds(10), 1 << 20),
            clock,
            Runnable::run);

    /** 받은 메시지와 닫힌 상태를 적어 두는 가짜 WebSocket 세션. */
    private static final class Socket {
        final List<String> received = new ArrayList<>();
        final Map<String, Object> attributes = new HashMap<>();
        CloseStatus closedWith;
        final WebSocketSession session;

        Socket(String id) {
            attributes.put(TicketHandshakeInterceptor.VERSION, CollabWebSocketHandler.PROTOCOL_VERSION);
            attributes.put(TicketHandshakeInterceptor.DOC_ID, DOC);
            attributes.put(TicketHandshakeInterceptor.TICKET,
                    new WsTicket(UUID.randomUUID(), DOC, PageRole.EDIT, "이름-" + id));
            session = (WebSocketSession) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {WebSocketSession.class}, (proxy, method, args) ->
                            switch (method.getName()) {
                                case "getId" -> id;
                                case "getAttributes" -> attributes;
                                case "isOpen" -> closedWith == null;
                                case "sendMessage" -> {
                                    received.add(((TextMessage) args[0]).getPayload());
                                    yield null;
                                }
                                case "close" -> {
                                    closedWith = args == null || args.length == 0 ? CloseStatus.NORMAL : (CloseStatus) args[0];
                                    yield null;
                                }
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
        }

        long count(String type) {
            return received.stream().filter(m -> m.contains("\"t\":\"" + type + "\"")).count();
        }
    }

    private Socket open(String id) throws Exception {
        Socket socket = new Socket(id);
        handler.afterConnectionEstablished(socket.session);
        send(socket, "{\"t\":\"hello\",\"clientId\":\"client-" + id + "\",\"lastRev\":0}");
        return socket;
    }

    private void send(Socket socket, String json) {
        handler.handleTextMessage(socket.session, new TextMessage(json));
    }

    private void moveCursor(Socket socket, int offset) {
        String position = "{\"blockId\":\"" + DOC + "\",\"offset\":" + offset + "}";
        send(socket, "{\"t\":\"presence\",\"cursor\":{\"anchor\":" + position + ",\"head\":" + position + "}}");
    }

    @Test
    void 초당_한도를_넘는_presence_는_퍼뜨리지_않고_버리며_연결은_끊지_않는다() throws Exception {
        Socket a = open("A");
        Socket b = open("B");
        long before = b.count("presence");

        for (int i = 0; i < 12; i++) {
            moveCursor(a, 0);
        }

        assertEquals(5, b.count("presence") - before);
        assertNull(a.closedWith);
    }

    @Test
    void 일초가_지나면_presence_를_다시_받는다() throws Exception {
        Socket a = open("A");
        Socket b = open("B");
        for (int i = 0; i < 5; i++) {
            moveCursor(a, 0);
        }
        long before = b.count("presence");

        moveCursor(a, 0);
        assertEquals(before, b.count("presence"));

        now = now.plusMillis(1001);
        moveCursor(a, 0);
        assertEquals(before + 1, b.count("presence"));
    }

    private void sendEmptyChange(Socket socket, long seq) {
        send(socket, "{\"t\":\"change\",\"clientSeq\":" + seq + ",\"baseRev\":0,\"ops\":[]}");
    }

    @Test
    void change_를_한도까지_보내도_presence_는_퍼진다() throws Exception {
        Socket a = open("A");
        Socket b = open("B");
        for (int seq = 1; seq <= 30; seq++) {
            sendEmptyChange(a, seq);
        }
        long before = b.count("presence");

        moveCursor(a, 0);

        // 카운터를 함께 쓰면 change 30 개가 presence 한도(5)를 이미 채워 이 presence 가 버려진다.
        assertEquals(before + 1, b.count("presence"));
        assertNull(a.closedWith);
    }

    @Test
    void presence_를_한도_넘게_보내도_change_는_받는다() throws Exception {
        Socket a = open("A");
        open("B");
        for (int i = 0; i < 12; i++) {
            moveCursor(a, 0);
        }
        for (int seq = 1; seq <= 30; seq++) {
            sendEmptyChange(a, seq);
        }

        // 빈 묶음은 nack 로 돌아온다. 카운터를 함께 쓰면 presence 가 채운 자리 때문에 4008 로 끊긴다.
        assertNull(a.closedWith);
        assertEquals(30, a.count("nack"));
    }

    @Test
    void 초당_한도를_넘는_change_는_지금처럼_4008_로_끊는다() throws Exception {
        Socket a = open("A");
        for (int seq = 1; seq <= 31; seq++) {
            sendEmptyChange(a, seq);
        }

        assertEquals(CloseCodes.PROTOCOL, a.closedWith.getCode());
    }
}
