package com.copage.collab.in;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.Connection;
import com.copage.collab.domain.ServerMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/**
 * WebSocket 세션 하나를 문서 세션이 쓰는 {@link Connection} 으로 감싼다.
 *
 * <p>보내기는 {@link ConcurrentWebSocketSessionDecorator} 가 연결마다 따로 쌓는다. 보내는 쪽 스레드는 막히지 않고, 쌓인 양이
 * 한도를 넘으면 그 연결만 4011 로 끊는다 (docs/05-PROTOCOL.md 7.7).
 */
final class WebSocketConnection implements Connection {

    private static final System.Logger LOG = System.getLogger(WebSocketConnection.class.getName());

    private final WebSocketSession raw;
    private final ConcurrentWebSocketSessionDecorator out;
    private final UUID userId;
    private final ProtocolCodec codec;

    WebSocketConnection(WebSocketSession raw, UUID userId, ProtocolCodec codec, int sendTimeLimitMillis, int bufferLimitBytes) {
        this.raw = raw;
        this.out = new ConcurrentWebSocketSessionDecorator(
                raw, sendTimeLimitMillis, bufferLimitBytes, ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        this.userId = userId;
        this.codec = codec;
    }

    @Override
    public String id() {
        return raw.getId();
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public void send(ServerMessage message) {
        if (!raw.isOpen()) {
            return;
        }
        try {
            out.sendMessage(new TextMessage(codec.write(message)));
        } catch (SessionLimitExceededException e) {
            close(CloseCodes.SLOW, "보낼 메시지가 너무 쌓였다");
        } catch (IOException | IllegalStateException e) {
            LOG.log(System.Logger.Level.DEBUG, "연결 " + id() + " 에 보내지 못했다", e);
        }
    }

    @Override
    public void close(int code, String reason) {
        try {
            raw.close(new CloseStatus(code, truncate(reason)));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "연결 " + id() + " 을 닫지 못했다", e);
        }
    }

    /** 닫는 이유는 123 바이트를 넘을 수 없다 (RFC 6455). */
    static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < reason.length(); ) {
            int codePoint = reason.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 120) {
                break;
            }
            out.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
