package com.copage.collab.in;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.domain.ClientMessage;
import com.copage.collab.domain.WsTicket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 문서 WebSocket 의 입구 (docs/05-PROTOCOL.md). 연결마다 hello 를 한 번 받은 뒤 change 를 문서 세션에 넘긴다.
 *
 * <p>여기서 지키는 규칙은 연결 단위의 것들이다. 티켓과 규약 버전, 메시지 순서(hello 가 먼저), 메시지 크기와 change 빈도,
 * 오래 아무것도 보내지 않는 연결 정리. 문서 단위의 규칙은 문서 세션이 지킨다.
 */
public final class CollabWebSocketHandler extends TextWebSocketHandler {

    static final String PROTOCOL_VERSION = "1";

    /** 핸들러가 쓰는 한도. */
    public record Limits(
            int maxMessageBytes,
            int maxChangesPerSecond,
            Duration idleTimeout,
            Duration sendTimeLimit,
            long sendBufferBytes) {
    }

    private static final class State {
        final WebSocketConnection connection;
        final UUID docId;
        final WsTicket ticket;
        final Deque<Instant> recentChanges = new ArrayDeque<>();
        volatile Instant lastReceived;
        boolean joined;

        State(WebSocketConnection connection, UUID docId, WsTicket ticket, Instant now) {
            this.connection = connection;
            this.docId = docId;
            this.ticket = ticket;
            this.lastReceived = now;
        }
    }

    private final DocumentSessions sessions;
    private final ProtocolCodec codec;
    private final Limits limits;
    private final Clock clock;
    private final Executor sendExecutor;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** @param sendExecutor 연결마다의 전송 작업을 돌리는 곳. 가상 스레드를 쓴다 */
    public CollabWebSocketHandler(
            DocumentSessions sessions, ProtocolCodec codec, Limits limits, Clock clock, Executor sendExecutor) {
        this.sessions = sessions;
        this.codec = codec;
        this.limits = limits;
        this.clock = clock;
        this.sendExecutor = sendExecutor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        if (!PROTOCOL_VERSION.equals(attributes.get(TicketHandshakeInterceptor.VERSION))) {
            session.close(new CloseStatus(CloseCodes.VERSION, "지원하지 않는 규약 버전"));
            return;
        }
        if (!(attributes.get(TicketHandshakeInterceptor.TICKET) instanceof WsTicket ticket)) {
            session.close(new CloseStatus(CloseCodes.TICKET, "티켓이 없거나 만료되었다"));
            return;
        }
        UUID docId = (UUID) attributes.get(TicketHandshakeInterceptor.DOC_ID);
        WebSocketConnection connection = new WebSocketConnection(
                session, ticket.userId(), codec, sendExecutor, clock, limits.sendTimeLimit(), limits.sendBufferBytes());
        states.put(session.getId(), new State(connection, docId, ticket, clock.instant()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        State state = states.get(session.getId());
        if (state == null) {
            return;
        }
        Instant now = clock.instant();
        state.lastReceived = now;
        try {
            if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > limits.maxMessageBytes()) {
                throw new ProtocolException("메시지가 " + limits.maxMessageBytes() + " 바이트를 넘는다");
            }
            handle(state, codec.read(message.getPayload()), now);
        } catch (ProtocolException e) {
            state.connection.close(CloseCodes.PROTOCOL, e.getMessage());
        }
    }

    // 같은 연결의 메시지는 차례로 들어오므로 State 의 joined 와 recentChanges 는 한 스레드씩만 만진다.
    private void handle(State state, ClientMessage message, Instant now) {
        switch (message) {
            case ClientMessage.Hello hello -> {
                if (state.joined) {
                    throw new ProtocolException("hello 는 한 번만 보낸다");
                }
                state.joined = true;
                sessions.join(state.docId, state.connection, hello, state.ticket.role());
            }
            case ClientMessage.Change change -> {
                if (!state.joined) {
                    throw new ProtocolException("hello 보다 change 가 먼저 왔다");
                }
                checkRate(state, now);
                sessions.submit(state.docId, state.connection, change);
            }
            case ClientMessage.Presence presence -> {
                // 프레즌스는 아직 구현하지 않았다. 받기만 하고 버린다.
            }
            case ClientMessage.Ping ping -> {
                // lastReceived 를 갱신한 것으로 충분하다.
            }
        }
    }

    private void checkRate(State state, Instant now) {
        Instant windowStart = now.minusSeconds(1);
        while (!state.recentChanges.isEmpty() && !state.recentChanges.peekFirst().isAfter(windowStart)) {
            state.recentChanges.pollFirst();
        }
        if (state.recentChanges.size() >= limits.maxChangesPerSecond()) {
            throw new ProtocolException("change 를 초당 " + limits.maxChangesPerSecond() + " 개보다 많이 보냈다");
        }
        state.recentChanges.addLast(now);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        State state = states.remove(session.getId());
        if (state != null && state.joined) {
            sessions.leave(state.docId, state.connection);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * idleTimeout 동안 아무 메시지도 보내지 않은 연결을 닫고, 쓰기가 오래 막힌 연결을 4011 로 닫는다. 설정에서 주기적으로
     * 부른다.
     */
    public void closeIdleConnections() {
        Instant deadline = clock.instant().minus(limits.idleTimeout());
        for (State state : states.values()) {
            if (state.lastReceived.isBefore(deadline)) {
                state.connection.close(CloseStatus.GOING_AWAY.getCode(), "응답이 없다");
            } else {
                state.connection.checkSendLimits();
            }
        }
    }
}
