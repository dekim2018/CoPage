package com.copage.collab.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.domain.ServerMessage;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** 받는 쪽이 느린 연결이 보내는 쪽 스레드를 막지 않고, 한도를 넘으면 그 연결만 4011 로 끊기는지 확인한다. */
class WebSocketConnectionTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ProtocolCodec codec = new ProtocolCodec(JsonMapper.builder().build());
    private final CountDownLatch unblock = new CountDownLatch(1);
    private final List<String> written = new CopyOnWriteArrayList<>();
    private volatile CloseStatus closedWith;
    private volatile Instant now = Instant.parse("2026-11-23T00:00:00Z");

    @AfterEach
    void tearDown() {
        unblock.countDown();
        executor.shutdownNow();
    }

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

    /** blocking 이면 sendMessage 가 unblock 될 때까지 돌아오지 않는다. 네트워크가 막힌 클라이언트다. */
    private WebSocketSession session(boolean blocking) {
        return (WebSocketSession) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {WebSocketSession.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getId":
                            return "s1";
                        case "isOpen":
                            return closedWith == null;
                        case "sendMessage":
                            if (blocking) {
                                unblock.await();
                            }
                            written.add(((TextMessage) args[0]).getPayload());
                            return null;
                        case "close":
                            closedWith = (CloseStatus) args[0];
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private WebSocketConnection connection(boolean blocking, long bufferLimitBytes) {
        return new WebSocketConnection(
                session(blocking), UUID.randomUUID(), null, codec, executor, clock, Duration.ofSeconds(10),
                bufferLimitBytes);
    }

    private void awaitClosed() throws InterruptedException {
        for (int i = 0; i < 100 && closedWith == null; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void 받는_쪽이_막혀도_보내는_쪽은_막히지_않고_대기열이_한도를_넘으면_4011_로_끊는다() throws Exception {
        WebSocketConnection connection = connection(true, 1_000);

        // 보내는 쪽이 막히면 2초 뒤 실패로 끝난다.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 1; i <= 100; i++) {
                connection.send(new ServerMessage.Ack(i, i));
            }
        });
        awaitClosed();

        assertEquals(CloseCodes.SLOW, closedWith.getCode());
    }

    @Test
    void 메시지_하나를_시간_한도보다_오래_쓰고_있으면_4011_로_끊는다() throws Exception {
        WebSocketConnection connection = connection(true, 1_000_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> connection.send(new ServerMessage.Ack(1, 1)));
        Thread.sleep(100);

        connection.checkSendLimits();
        Thread.sleep(50);
        assertNull(closedWith, "아직 한도 안이다");

        now = now.plusSeconds(11);
        connection.checkSendLimits();
        awaitClosed();

        assertEquals(CloseCodes.SLOW, closedWith.getCode());
    }

    @Test
    void 막히지_않는_연결은_보낸_순서대로_받는다() throws Exception {
        WebSocketConnection connection = connection(false, 1_000_000);
        for (int i = 1; i <= 200; i++) {
            connection.send(new ServerMessage.Ack(i, i));
        }
        for (int i = 0; i < 100 && written.size() < 200; i++) {
            Thread.sleep(20);
        }

        assertEquals(200, written.size());
        for (int i = 0; i < 200; i++) {
            assertTrue(written.get(i).contains("\"clientSeq\":" + (i + 1) + ","), written.get(i));
        }
        assertNull(closedWith);
    }
}
