package com.copage.loadgen;

import com.copage.collab.domain.OpJson;
import com.copage.ot.FractionalIndex;
import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.sync.ClientSession;
import com.copage.ot.sync.CommittedChange;
import com.copage.ot.sync.Submission;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 가상 편집자 하나. 브라우저 클라이언트처럼 ClientSession 으로 맞추고, 묶음은 하나씩, 50ms 에 많아야 하나 보낸다(D-34).
 *
 * <p>칠 때는 커서 자리에 글자 하나(대부분), Enter(블록 나누기), Backspace(글자 하나 지우기)를 고른다. 들어오면 자기 문단을
 * 하나 만들고 그 끝에서 시작한다. 커서가 가리키던 블록이 다른 사람의 편집으로 사라지면 보이는 블록 하나의 끝으로 옮긴다.
 */
final class VirtualEditor implements WebSocket.Listener {

    /** 모든 편집자가 함께 쓰는 측정값. 같은 JVM 의 같은 시계로 잰다. */
    static final class Shared {
        final Map<String, Long> sentAt = new ConcurrentHashMap<>();
        final Stats ack = new Stats();
        final Stats remote = new Stats();
        final Stats open = new Stats();
        final AtomicInteger nacks = new AtomicInteger();
        final AtomicInteger changes = new AtomicInteger();
        final Map<Integer, AtomicInteger> closes = new ConcurrentHashMap<>();
    }

    private static final long CHANGE_INTERVAL_MS = 50;
    /** 편집자마다 HttpClient 를 만들면 선택자 스레드가 편집자 수만큼 생긴다. 하나를 함께 쓴다. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz가나다라마바사 ";

    private final String clientId;
    private final String pageId;
    private final Shared shared;
    private final Random random;
    private final StringBuilder partial = new StringBuilder();
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private WebSocket socket;
    private ClientSession session;
    private long connectStartedNanos;
    private long lastSendAt;
    private String caretBlock;
    private int caretOffset;
    private int counter;

    VirtualEditor(String clientId, String pageId, Shared shared, long seed) {
        this.clientId = clientId;
        this.pageId = pageId;
        this.shared = shared;
        this.random = new Random(seed);
    }

    void connect(String wsBase, String ticket) throws Exception {
        connectStartedNanos = System.nanoTime();
        socket = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(wsBase + "/ws/docs/" + pageId + "?ticket=" + ticket + "&v=1"), this)
                .get(10, TimeUnit.SECONDS);
        ObjectNode hello = Api.MAPPER.createObjectNode();
        hello.put("t", "hello");
        hello.put("clientId", clientId);
        hello.put("lastRev", 0);
        socket.sendText(Api.MAPPER.writeValueAsString(hello), true).get(5, TimeUnit.SECONDS);
        if (!welcomed.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(clientId + " 가 welcome 을 받지 못했다");
        }
    }

    /** 글자 하나를 친다(또는 Enter, Backspace). 사람의 한 번 누름이다. */
    synchronized void typeOnce() {
        if (session == null) {
            return;
        }
        Document view = session.view();
        ensureCaret(view);
        Block block = view.get(caretBlock);
        int roll = random.nextInt(100);
        List<Op> ops;
        if (roll < 8 && block.parentId() != null) {
            String newId = clientId + "-b" + (counter++);
            ops = List.of(new SplitBlock(caretBlock, caretOffset, newId, block.parentId(), posAfter(view, block),
                    block.type(), block.props()));
            caretBlock = newId;
            caretOffset = 0;
        } else if (roll < 16 && caretOffset > 0) {
            ops = List.of(new TextOp(caretBlock, Delta.builder().retain(caretOffset - 1).delete(1).build()));
            caretOffset--;
        } else {
            String letter = String.valueOf(LETTERS.charAt(random.nextInt(LETTERS.length())));
            ops = List.of(new TextOp(caretBlock, Delta.builder().retain(caretOffset).insert(letter).build()));
            caretOffset++;
        }
        session.edit(ops);
        flush();
    }

    /** 정해진 연산을 그대로 편집한다. 문서를 미리 채울 때 쓴다. */
    synchronized void submit(List<Op> ops) {
        session.edit(ops);
        flush();
    }

    synchronized boolean hasPending() {
        return session == null || session.hasPending();
    }

    synchronized long confirmedRev() {
        return session == null ? -1 : session.confirmedRev();
    }

    synchronized Document view() {
        return session.view();
    }

    synchronized void flush() {
        if (session == null || System.currentTimeMillis() - lastSendAt < CHANGE_INTERVAL_MS) {
            return;
        }
        Optional<Submission> next = session.poll();
        next.ifPresent(this::send);
    }

    void close() {
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "끝");
        }
    }

    private void send(Submission submission) {
        lastSendAt = System.currentTimeMillis();
        shared.sentAt.put(key(submission.clientId(), submission.clientSeq()), System.nanoTime());
        shared.changes.incrementAndGet();
        ObjectNode change = Api.MAPPER.createObjectNode();
        change.put("t", "change");
        change.put("clientSeq", submission.clientSeq());
        change.put("baseRev", submission.baseRev());
        change.set("ops", OpJson.ops(submission.ops()));
        socket.sendText(Api.MAPPER.writeValueAsString(change), true);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            handle(Api.MAPPER.readTree(text));
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        shared.closes.computeIfAbsent(statusCode, code -> new AtomicInteger()).incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    private synchronized void handle(JsonNode message) {
        long now = System.nanoTime();
        switch (message.get("t").stringValue()) {
            case "welcome" -> {
                session = new ClientSession(clientId, OpJson.readDocument(message.get("snapshot")), message.get("rev").longValue());
                shared.open.add((now - connectStartedNanos) / 1e6);
                makeOwnParagraph();
                welcomed.countDown();
            }
            case "ack" -> {
                long seq = message.get("clientSeq").longValue();
                Long sent = shared.sentAt.get(key(clientId, seq));
                if (sent != null) {
                    shared.ack.add((now - sent) / 1e6);
                }
                session.onAck(seq, message.get("rev").longValue());
                flush();
            }
            case "nack" -> {
                shared.nacks.incrementAndGet();
                session.onNack(message.get("clientSeq").longValue());
                flush();
            }
            case "remote" -> {
                CommittedChange change = new CommittedChange(message.get("rev").longValue(), message.get("clientId").stringValue(),
                        message.get("clientSeq").longValue(), OpJson.readOps(message.get("ops")));
                Long sent = shared.sentAt.get(key(change.clientId(), change.clientSeq()));
                if (sent != null) {
                    shared.remote.add((now - sent) / 1e6);
                }
                session.onChange(change);
                flush();
            }
            default -> {
                // presence, role
            }
        }
    }

    /** 들어오면 루트 맨 뒤에 자기 문단을 하나 만들고 그 안에서 친다. */
    private void makeOwnParagraph() {
        Document view = session.view();
        List<Block> children = view.children(view.rootId());
        String last = children.isEmpty() ? null : children.get(children.size() - 1).pos();
        caretBlock = clientId + "-p";
        caretOffset = 0;
        session.edit(List.of(new InsertBlock(caretBlock, view.rootId(), FractionalIndex.between(last, null), "paragraph",
                Attrs.NONE, RichText.EMPTY)));
        flush();
    }

    private void ensureCaret(Document view) {
        if (caretBlock != null && view.get(caretBlock) != null && view.isVisible(caretBlock)) {
            caretOffset = Math.min(caretOffset, view.get(caretBlock).text().length());
            return;
        }
        List<Block> visible = new ArrayList<>();
        for (Block block : view.blocks()) {
            if (block.parentId() != null && view.isVisible(block.id())) {
                visible.add(block);
            }
        }
        Block pick = visible.isEmpty() ? view.get(view.rootId()) : visible.get(random.nextInt(visible.size()));
        caretBlock = pick.id();
        caretOffset = pick.text().length();
    }

    private static String posAfter(Document view, Block block) {
        List<Block> siblings = view.children(block.parentId());
        int index = siblings.indexOf(block);
        String next = index + 1 < siblings.size() ? siblings.get(index + 1).pos() : null;
        return FractionalIndex.between(block.pos(), next);
    }

    private static String key(String clientId, long seq) {
        return clientId + "#" + seq;
    }
}
