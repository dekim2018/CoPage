package com.copage.collab.in;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.CollabMetrics;
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
 * <p>여기서 지키는 규칙은 연결 단위의 것들이다. 티켓과 규약 버전, 메시지 순서(hello 가 먼저), 메시지 크기, change 와
 * presence 빈도, 오래 아무것도 보내지 않는 연결 정리. 문서 단위의 규칙은 문서 세션이 지킨다.
 *
 * <p>presence 는 문서의 모든 연결로 퍼지므로, 한 연결이 많이 보내면 받는 쪽 전부의 대기열이 찬다. 한도를 넘는 presence 는
 * 연결을 끊지 않고 버린다. 정상 클라이언트는 50ms 마다 많아야 하나를 보내므로(초당 20개) 기본 한도 30 에 걸리지 않는다.
 *
 * <p>한계: 버린 것이 몰아 보낸 것의 마지막(가장 최신) 커서였다면, 그 연결이 다음 presence 를 보낼 때까지 남들 화면에는 그 전
 * 커서가 남는다. 포커스를 잃어 커서를 지우는 presence 도 버려질 수 있다. 한도를 넘는 것은 정상 클라이언트가 아니라는 전제로
 * 받아들인다. 정상 클라이언트가 걸리는지 보려고 연결마다 처음 버릴 때 DEBUG 로그를 남긴다.
 */
public final class CollabWebSocketHandler extends TextWebSocketHandler {

    private static final System.Logger LOG = System.getLogger(CollabWebSocketHandler.class.getName());

    static final String PROTOCOL_VERSION = "1";

    /** 핸들러가 쓰는 한도. */
    public record Limits(
            int maxMessageBytes,
            int maxChangesPerSecond,
            int maxPresencePerSecond,
            Duration idleTimeout,
            Duration sendTimeLimit,
            long sendBufferBytes) {
    }

    private static final class State {
        final WebSocketConnection connection;
        final UUID docId;
        final WsTicket ticket;
        final Deque<Instant> recentChanges = new ArrayDeque<>();
        final Deque<Instant> recentPresence = new ArrayDeque<>();
        boolean presenceDropped;
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
    private final CollabMetrics metrics;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** @param sendExecutor 연결마다의 전송 작업을 돌리는 곳. 가상 스레드를 쓴다 */
    public CollabWebSocketHandler(
            DocumentSessions sessions,
            ProtocolCodec codec,
            Limits limits,
            Clock clock,
            Executor sendExecutor,
            CollabMetrics metrics) {
        this.sessions = sessions;
        this.codec = codec;
        this.limits = limits;
        this.clock = clock;
        this.sendExecutor = sendExecutor;
        this.metrics = metrics;
    }

    /** 열린 WebSocket 연결 수(hello 전 포함). 지표가 읽는다. */
    public int connectionCount() {
        return states.size();
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
                session, ticket.userId(), ticket.shareLinkId(), codec, sendExecutor, clock, limits.sendTimeLimit(), limits.sendBufferBytes());
        connection.color(ticket.color());
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
            ClientMessage parsed = codec.read(message.getPayload());
            if (parsed instanceof ClientMessage.Change) {
                metrics.changeBytes(message.getPayloadLength());
            }
            handle(state, parsed, now);
        } catch (ProtocolException e) {
            // 정상 클라이언트는 규약을 어기지 않는다. 끊긴 쪽은 다시 붙지 않으므로(4008 은 치명) 원인을 남긴다.
            LOG.log(System.Logger.Level.WARNING, "연결 " + state.connection.id() + " 을 규약 위반으로 닫는다: " + e.getMessage());
            state.connection.close(CloseCodes.PROTOCOL, e.getMessage());
        }
    }

    // 같은 연결의 메시지는 차례로 들어오므로 State 의 joined, recentChanges, recentPresence, presenceDropped 는 한 스레드씩만
    // 만진다.
    private void handle(State state, ClientMessage message, Instant now) {
        switch (message) {
            case ClientMessage.Hello hello -> {
                if (state.joined) {
                    throw new ProtocolException("hello 는 한 번만 보낸다");
                }
                state.joined = true;
                sessions.join(state.docId, state.connection, hello, state.ticket.role(), state.ticket.name());
            }
            case ClientMessage.Change change -> {
                if (!state.joined) {
                    throw new ProtocolException("hello 보다 change 가 먼저 왔다");
                }
                if (!withinRate(state.recentChanges, limits.maxChangesPerSecond(), now)) {
                    throw new ProtocolException("change 를 초당 " + limits.maxChangesPerSecond() + " 개보다 많이 보냈다");
                }
                sessions.submit(state.docId, state.connection, change);
            }
            case ClientMessage.Presence presence -> {
                if (!state.joined) {
                    throw new ProtocolException("hello 보다 presence 가 먼저 왔다");
                }
                if (withinRate(state.recentPresence, limits.maxPresencePerSecond(), now)) {
                    sessions.presence(state.docId, state.connection, presence.cursor());
                } else if (!state.presenceDropped) {
                    state.presenceDropped = true;
                    LOG.log(System.Logger.Level.DEBUG, "연결 " + state.connection.id() + " 이 presence 를 초당 "
                            + limits.maxPresencePerSecond() + " 개보다 많이 보내 넘는 것을 버린다");
                }
            }
            case ClientMessage.Ping ping -> {
                // lastReceived 를 갱신한 것으로 충분하다.
            }
        }
    }

    /** 최근 1초 동안 받은 수가 한도 안이면 이번 것을 세고 true. 넘으면 세지 않고 false. */
    private static boolean withinRate(Deque<Instant> recent, int perSecond, Instant now) {
        Instant windowStart = now.minusSeconds(1);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(windowStart)) {
            recent.pollFirst();
        }
        if (recent.size() >= perSecond) {
            return false;
        }
        recent.addLast(now);
        return true;
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
