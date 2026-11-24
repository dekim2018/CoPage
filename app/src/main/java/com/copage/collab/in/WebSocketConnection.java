package com.copage.collab.in;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.Connection;
import com.copage.collab.domain.ServerMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 세션 하나를 문서 세션이 쓰는 {@link Connection} 으로 감싼다.
 *
 * <p>보내기는 연결마다 따로 쌓고, 실제 쓰기는 연결마다 하나씩 도는 전송 작업(가상 스레드)이 한다. 문서 세션의 스레드는
 * 대기열에 넣기만 하고 바로 돌아오므로, 받는 쪽이 느린 연결 하나가 같은 문서의 다른 연결을 기다리게 하지 않는다. 대기열이
 * bufferLimitBytes 를 넘거나 메시지 하나를 sendTimeLimit 보다 오래 쓰고 있으면 그 연결만 4011 로 끊는다
 * (docs/05-PROTOCOL.md 7.7).
 *
 * <p>Spring 의 ConcurrentWebSocketSessionDecorator 는 쓰지 않는다. 그 클래스는 잠금을 얻은 호출 스레드가 직접 쓰고, 한도는
 * 잠금을 얻지 못한 다른 스레드만 검사한다. 모든 전송이 문서 세션의 한 스레드에서 나가는 이 구조에서는 그 스레드가 쓰기에
 * 막히고 한도 검사도 일어나지 않는다.
 */
final class WebSocketConnection implements Connection {

    private static final System.Logger LOG = System.getLogger(WebSocketConnection.class.getName());

    private final WebSocketSession raw;
    private final UUID userId;
    private final ProtocolCodec codec;
    private final Executor executor;
    private final Clock clock;
    private final Duration sendTimeLimit;
    private final long bufferLimitBytes;

    // 아래는 this 로 잠그고 읽고 쓴다.
    private final Deque<TextMessage> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean sending;
    private Instant sendingSince;
    private boolean closed;

    WebSocketConnection(
            WebSocketSession raw,
            UUID userId,
            ProtocolCodec codec,
            Executor executor,
            Clock clock,
            Duration sendTimeLimit,
            long bufferLimitBytes) {
        this.raw = raw;
        this.userId = userId;
        this.codec = codec;
        this.executor = executor;
        this.clock = clock;
        this.sendTimeLimit = sendTimeLimit;
        this.bufferLimitBytes = bufferLimitBytes;
    }

    @Override
    public String id() {
        return raw.getId();
    }

    @Override
    public UUID userId() {
        return userId;
    }

    /** 대기열에 넣고 바로 돌아온다. 쓰기는 전송 작업이 한다. */
    @Override
    public void send(ServerMessage message) {
        TextMessage text = new TextMessage(codec.write(message));
        boolean start = false;
        String overflow;
        synchronized (this) {
            if (closed) {
                return;
            }
            queue.addLast(text);
            queuedBytes += text.getPayloadLength();
            overflow = limitExceeded();
            if (overflow == null && !sending) {
                sending = true;
                start = true;
            }
        }
        if (overflow != null) {
            close(CloseCodes.SLOW, overflow);
        } else if (start) {
            run(this::drain);
        }
    }

    /**
     * 더 보낼 메시지가 없어도 쓰기가 오래 막혀 있으면 끊는다. 한도 검사는 send 에서도 하지만, 막힌 뒤로 보낼 메시지가 없으면
     * 검사할 기회가 없어서 주기적으로 부른다.
     */
    void checkSendLimits() {
        String overflow;
        synchronized (this) {
            overflow = closed ? null : limitExceeded();
        }
        if (overflow != null) {
            close(CloseCodes.SLOW, overflow);
        }
    }

    /** 닫기도 전송 작업과 같은 쪽에서 한다. 쓰기에 막힌 연결을 닫다가 호출한 스레드가 함께 막히지 않게 하기 위해서다. */
    @Override
    public void close(int code, String reason) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            queuedBytes = 0;
        }
        if (code == CloseCodes.SLOW) {
            // 받는 쪽이 막힌 연결이라 닫기 프레임이 닿지 못할 수 있다(클라이언트에는 1006). 운영에서 원인을 알 수 있게 남긴다.
            LOG.log(System.Logger.Level.WARNING, "연결 " + id() + " 을 느려서 닫는다: " + reason);
        }
        run(() -> {
            try {
                raw.close(new CloseStatus(code, truncate(reason)));
            } catch (IOException | IllegalStateException e) {
                LOG.log(System.Logger.Level.DEBUG, "연결 " + id() + " 을 닫지 못했다", e);
            }
        });
    }

    private String limitExceeded() {
        if (queuedBytes > bufferLimitBytes) {
            return "보낼 메시지가 너무 쌓였다";
        }
        if (sendingSince != null && sendingSince.plus(sendTimeLimit).isBefore(clock.instant())) {
            return "메시지를 받는 속도가 너무 느리다";
        }
        return null;
    }

    private void drain() {
        while (true) {
            TextMessage next;
            synchronized (this) {
                next = closed ? null : queue.pollFirst();
                if (next == null) {
                    sending = false;
                    sendingSince = null;
                    return;
                }
                queuedBytes -= next.getPayloadLength();
                sendingSince = clock.instant();
            }
            try {
                raw.sendMessage(next);
            } catch (IOException | IllegalStateException e) {
                // 연결이 끊겼다. 남은 메시지는 보낼 곳이 없다. 재접속한 클라이언트는 welcome 으로 다시 맞춘다.
                LOG.log(System.Logger.Level.DEBUG, "연결 " + id() + " 에 보내지 못했다", e);
                synchronized (this) {
                    queue.clear();
                    queuedBytes = 0;
                    sending = false;
                    sendingSince = null;
                }
                return;
            }
        }
    }

    private void run(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // 서버가 내려가는 중이다. 연결은 컨테이너가 닫는다.
            LOG.log(System.Logger.Level.DEBUG, "실행기가 닫혀 연결 " + id() + " 의 일을 버렸다", e);
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
