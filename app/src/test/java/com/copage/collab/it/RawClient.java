package com.copage.collab.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 규약 메시지를 그대로 주고받는 JDK WebSocket 클라이언트. 장애 실험에서 TestClient 로 할 수 없는 일을 한다.
 *
 * <ul>
 *   <li>큰 메시지를 받는다. Spring 의 표준 클라이언트는 받는 버퍼가 8KB 라 그보다 큰 메시지에서 끊긴다
 *   <li>읽기를 멈춘다({@link #pauseAfterWelcome}). JDK 클라이언트는 request 한 만큼만 읽으므로, 더 청하지 않으면 소켓에서
 *       읽지 않고 TCP 버퍼가 차서 서버의 쓰기가 막힌다. 받는 쪽이 느린 클라이언트를 흉내 낸다
 * </ul>
 */
final class RawClient implements WebSocket.Listener {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record Received(long atNanos, JsonNode message) {
    }

    final BlockingQueue<Received> inbox = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    private final boolean pauseAfterWelcome;
    private WebSocket socket;
    private volatile boolean paused;

    RawClient(boolean pauseAfterWelcome) {
        this.pauseAfterWelcome = pauseAfterWelcome;
    }

    /** 붙어서 hello 를 보내고 welcome 을 받는다. */
    JsonNode connect(URI uri, String clientId) throws Exception {
        socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, this).get(10, TimeUnit.SECONDS);
        send("{\"t\":\"hello\",\"clientId\":\"" + clientId + "\",\"lastRev\":0}");
        Received welcome = inbox.poll(10, TimeUnit.SECONDS);
        if (welcome == null || !"welcome".equals(welcome.message().get("t").stringValue())) {
            throw new AssertionError(clientId + " 가 welcome 을 받지 못했다: " + welcome);
        }
        return welcome.message();
    }

    synchronized void send(String json) throws Exception {
        socket.sendText(json, true).get(10, TimeUnit.SECONDS);
    }

    /** 다음 메시지 중 t 가 type 인 것을 기다린다. 사이의 다른 메시지는 버린다. */
    Received await(String type, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Received received = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (received != null && type.equals(received.message().get("t").stringValue())) {
                return received;
            }
        }
        throw new AssertionError(type + " 를 " + timeoutMillis + "ms 안에 받지 못했다");
    }

    /** 멈췄던 읽기를 다시 시작한다. */
    void resume() {
        paused = false;
        socket.request(Long.MAX_VALUE);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            JsonNode message = MAPPER.readTree(partial.toString());
            partial.setLength(0);
            inbox.add(new Received(System.nanoTime(), message));
            if (pauseAfterWelcome && "welcome".equals(message.get("t").stringValue())) {
                paused = true;
            }
        }
        if (!paused) {
            webSocket.request(1);
        }
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(1006);
    }
}
