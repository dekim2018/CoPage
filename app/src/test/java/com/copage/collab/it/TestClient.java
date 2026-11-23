package com.copage.collab.it;

import com.copage.collab.domain.OpJson;
import com.copage.ot.op.Op;
import com.copage.ot.sync.ClientSession;
import com.copage.ot.sync.CommittedChange;
import com.copage.ot.sync.Submission;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 WebSocket 으로 서버에 붙는 테스트 클라이언트. 상태 기계는 ot-core 의 ClientSession 을 그대로 쓴다.
 *
 * <p>받은 메시지는 큐에 쌓아 두고 테스트 스레드가 {@link #process()} 에서 처리한다. ClientSession 은 테스트 스레드에서만
 * 만진다. 연결마다 번호를 붙여, 끊긴 연결로 늦게 도착한 메시지는 버린다.
 */
public final class TestClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record Incoming(int generation, JsonNode message) {
    }

    record Closed(int generation, int code) {
    }

    final String clientId;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final BlockingQueue<Incoming> inbox = new LinkedBlockingQueue<>();
    final List<Closed> closes = new ArrayList<>();
    private WebSocketSession socket;
    private int generation;
    private boolean welcomed;
    ClientSession session;
    int nacks;

    public TestClient(String clientId) {
        this.clientId = clientId;
    }

    /** 티켓을 붙여 연결하고 hello 를 보낸다. 확정 문서가 있으면 이어 붙는다. */
    public void connect(URI uri) throws Exception {
        int myGeneration = ++generation;
        welcomed = false;
        socket = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                inbox.add(new Incoming(myGeneration, MAPPER.readTree(message.getPayload())));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                synchronized (closes) {
                    closes.add(new Closed(myGeneration, status.getCode()));
                }
            }
        }, uri.toString()).get(5, TimeUnit.SECONDS);
        ObjectNode hello = MAPPER.createObjectNode();
        hello.put("t", "hello");
        hello.put("clientId", clientId);
        hello.put("lastRev", session == null ? 0 : session.confirmedRev());
        hello.put("resume", session != null);
        try {
            send(hello);
        } catch (IllegalStateException | java.io.IOException e) {
            // 서버가 티켓이나 버전을 보고 곧바로 닫았다. 닫힌 코드는 closes 에 남는다.
        }
    }

    void disconnect() throws Exception {
        generation++;
        welcomed = false;
        if (socket != null && socket.isOpen()) {
            socket.close();
        }
    }

    boolean connected() {
        return socket != null && socket.isOpen() && welcomed;
    }

    void edit(List<Op> ops) throws Exception {
        session.edit(ops);
        flush();
    }

    /** 쌓인 메시지를 처리한다. wait 만큼 첫 메시지를 기다린다. 하나라도 처리했으면 true. */
    boolean process(long waitMillis) throws Exception {
        Incoming first = inbox.poll(waitMillis, TimeUnit.MILLISECONDS);
        if (first == null) {
            return false;
        }
        List<Incoming> batch = new ArrayList<>();
        batch.add(first);
        inbox.drainTo(batch);
        for (Incoming incoming : batch) {
            if (incoming.generation() == generation) {
                handle(incoming.message());
            }
        }
        return true;
    }

    /** 다음 메시지 하나를 처리하지 않고 꺼낸다. 규약 검사 테스트에서 쓴다. */
    public JsonNode take() throws InterruptedException {
        Incoming incoming = inbox.poll(5, TimeUnit.SECONDS);
        return incoming == null ? null : incoming.message();
    }

    void send(JsonNode message) throws Exception {
        socket.sendMessage(new TextMessage(MAPPER.writeValueAsString(message)));
    }

    private void handle(JsonNode message) throws Exception {
        switch (message.get("t").stringValue()) {
            case "welcome" -> {
                if (message.has("snapshot")) {
                    if (session != null) {
                        throw new AssertionError(clientId + " 가 이어 붙는데 전체 문서를 받았다: " + message);
                    }
                    session = new ClientSession(
                            clientId, OpJson.readDocument(message.get("snapshot")), message.get("rev").longValue());
                    welcomed = true;
                    flush();
                } else {
                    List<CommittedChange> missed = new ArrayList<>();
                    message.get("ops").forEach(change -> missed.add(committed(change)));
                    welcomed = true;
                    session.resync(missed).ifPresent(this::sendQuietly);
                }
            }
            case "ack" -> {
                session.onAck(message.get("clientSeq").longValue(), message.get("rev").longValue());
                flush();
            }
            case "nack" -> {
                nacks++;
                session.onNack(message.get("clientSeq").longValue());
                flush();
            }
            case "remote" -> {
                session.onChange(committed(message));
                flush();
            }
            case "presence" -> {
                // 편집만 보는 클라이언트라 남의 커서와 입장, 퇴장은 쓰지 않는다.
            }
            default -> throw new AssertionError("알 수 없는 메시지: " + message);
        }
    }

    private void flush() {
        if (connected()) {
            session.poll().ifPresent(this::sendQuietly);
        }
    }

    private void sendQuietly(Submission submission) {
        ObjectNode change = MAPPER.createObjectNode();
        change.put("t", "change");
        change.put("clientSeq", submission.clientSeq());
        change.put("baseRev", submission.baseRev());
        change.set("ops", OpJson.ops(submission.ops()));
        try {
            send(change);
        } catch (Exception e) {
            // 보내는 사이 끊겼다. 재접속하면 resync 가 다시 보낸다.
        }
    }

    private static CommittedChange committed(JsonNode node) {
        return new CommittedChange(
                node.get("rev").longValue(),
                node.get("clientId").stringValue(),
                node.get("clientSeq").longValue(),
                OpJson.readOps(node.get("ops")));
    }

    Optional<Closed> lastClose() {
        synchronized (closes) {
            return closes.isEmpty() ? Optional.empty() : Optional.of(closes.get(closes.size() - 1));
        }
    }
}
