package com.copage.collab.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.ServerMessage.Ack;
import com.copage.collab.domain.ServerMessage.Nack;
import com.copage.collab.domain.ServerMessage.Remote;
import com.copage.collab.domain.ServerMessage.Welcome;
import com.copage.collab.domain.StoredChange;
import com.copage.collab.out.InMemoryDocumentStore;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 문서 세션의 규칙을 네트워크 없이 확인한다. 실행기와 예약과 시계를 테스트가 직접 돌린다. */
class DocumentSessionTest {

    private static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ROOT = DOC.toString();

    /** 맡긴 일을 쌓아 두었다가 runAll 에서 돌린다. 여러 묶음이 한 번에 커밋되는 경우를 만들 수 있다. */
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-23T00:00:00Z");
    private final CountingStore store = new CountingStore();
    private final DocumentSessions sessions = sessions(2);

    private DocumentSessions sessions(int maxConnections) {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new DocumentSessions(
                store,
                new ChangePolicy(20, 100),
                new DocumentSession.Settings(maxConnections, 3, Duration.ofDays(7), Duration.ofSeconds(60)),
                tasks::add,
                (task, delay) -> scheduled.add(task),
                clock);
    }

    /** 메모리 저장소에 append 횟수를 세고, 원하면 실패하게 하는 기능을 더했다. */
    private static final class CountingStore implements DocumentStore {
        final InMemoryDocumentStore delegate = new InMemoryDocumentStore();
        final List<Integer> appendSizes = new ArrayList<>();
        boolean fail;
        boolean failLoad;

        @Override
        public Snapshot load(UUID docId) {
            if (failLoad) {
                throw new IllegalStateException("저장소에 닿을 수 없다");
            }
            return delegate.load(docId);
        }

        @Override
        public List<StoredChange> changesSince(UUID docId, long fromRev) {
            return delegate.changesSince(docId, fromRev);
        }

        @Override
        public OptionalLong committedRev(UUID docId, String clientId, long clientSeq) {
            return delegate.committedRev(docId, clientId, clientSeq);
        }

        @Override
        public void append(UUID docId, List<StoredChange> changes) {
            if (fail) {
                throw new RevisionConflictException("다른 노드가 먼저 썼다");
            }
            appendSizes.add(changes.size());
            delegate.append(docId, changes);
        }
    }

    private void runAll() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    private FakeConnection join(String id, long lastRev, PageRole role) {
        FakeConnection connection = new FakeConnection(id);
        sessions.join(DOC, connection, new Hello("client-" + id, lastRev, lastRev > 0), role);
        runAll();
        return connection;
    }

    private FakeConnection join(String id) {
        return join(id, 0, PageRole.EDIT);
    }

    private void submit(FakeConnection connection, long seq, long baseRev, Op... ops) {
        sessions.submit(DOC, connection, new Change(seq, baseRev, List.of(ops)));
    }

    private static Op paragraph(String id, String text) {
        return new InsertBlock(id, ROOT, "a", "paragraph", Attrs.NONE, RichText.of(text));
    }

    private static Op type(String blockId, int offset, String text) {
        return new TextOp(blockId, Delta.builder().retain(offset).insert(text).build());
    }

    private String text(String blockId) {
        return store.load(DOC).document().get(blockId).text().plainText();
    }

    @Test
    void 처음_여는_문서는_루트_블록만_있는_스냅샷을_받는다() {
        FakeConnection a = join("A");

        Welcome welcome = a.last(Welcome.class);
        assertEquals(0, welcome.rev());
        assertTrue(welcome.rebase());
        assertNull(welcome.ops());
        assertEquals(1, welcome.snapshot().blockCount());
        assertEquals(ROOT, welcome.snapshot().rootId());
    }

    @Test
    void 두_사람이_동시에_보내면_먼저_온_쪽부터_확정하고_나중_쪽은_변환해서_확정한다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        submit(a, 1, 0, paragraph("X", "본문"));
        runAll();
        // b 는 X 를 받기 전, 리비전 1 을 기준으로 X 의 앞과 끝에 동시에 글자를 넣는다.
        submit(a, 2, 1, type("X", 0, "안녕 "));
        submit(b, 1, 1, type("X", 2, " 끝"));
        runAll();

        assertEquals("안녕 본문 끝", text("X"));
        assertEquals(List.of(new Ack(1, 1), new Ack(2, 2)), a.received.subList(1, 3));
        assertEquals(3, a.last(Remote.class).change().rev());
        assertEquals(new Ack(1, 3), b.last());
        // b 의 묶음은 a 의 삽입만큼 뒤로 밀려 확정되었다.
        assertEquals(List.of(type("X", 5, " 끝")), a.last(Remote.class).change().ops());
    }

    @Test
    void 같은_차례에_도착한_묶음은_한_번에_커밋한다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        submit(a, 1, 0, paragraph("X", "가"));
        submit(b, 1, 0, paragraph("Y", "나"));
        runAll();

        assertEquals(List.of(2), store.appendSizes);
        assertEquals(new Ack(1, 1), a.received.get(1));
        assertEquals(new Ack(1, 2), b.received.get(2));
    }

    @Test
    void 커밋에_실패하면_ack_를_보내지_않고_세션을_버린다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        store.fail = true;
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();

        assertEquals(1, a.received.size(), "welcome 말고는 아무것도 받지 않았다");
        assertEquals(1, b.received.size());
        assertEquals(CloseCodes.MOVED, a.closedWith);
        assertEquals(CloseCodes.MOVED, b.closedWith);
        assertEquals(0, sessions.size());

        // 다시 붙으면 저장소 기준으로 새 세션이 올라온다.
        store.fail = false;
        FakeConnection again = join("A2");
        assertEquals(0, again.last(Welcome.class).rev());
    }

    @Test
    void 재접속하면_놓친_묶음을_받고_이미_확정된_순번을_다시_보내면_처음_리비전으로_ack_한다() {
        FakeConnection a = join("A");
        submit(a, 1, 0, paragraph("X", "가"));
        submit(a, 2, 0, paragraph("Y", "나"));
        runAll();
        sessions.leave(DOC, a);
        runAll();

        FakeConnection again = new FakeConnection("A-again");
        sessions.join(DOC, again, new Hello("client-A", 1, true), PageRole.EDIT);
        runAll();
        Welcome welcome = again.last(Welcome.class);
        assertEquals(2, welcome.rev());
        assertEquals(List.of(2L), welcome.ops().stream().map(StoredChange::rev).toList());

        submit(again, 2, 1, paragraph("Y", "나"));
        runAll();
        assertEquals(new Ack(2, 2), again.last());
        assertEquals(2, store.load(DOC).rev());
    }

    @Test
    void 세션이_내려간_뒤에도_이미_확정된_순번은_저장소로_걸러_낸다() {
        FakeConnection a = join("A");
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();
        sessions.leave(DOC, a);
        runAll();
        now = now.plusSeconds(61);
        scheduled.forEach(Runnable::run);
        runAll();
        assertEquals(0, sessions.size());

        FakeConnection again = new FakeConnection("A-again");
        sessions.join(DOC, again, new Hello("client-A", 1, true), PageRole.EDIT);
        runAll();
        // 새 세션의 ServerDocument 는 순번 1 을 모른다.
        sessions.submit(DOC, again, new Change(1, 1, List.of(paragraph("X", "가"))));
        runAll();

        assertEquals(new Ack(1, 1), again.last());
        assertEquals(1, store.load(DOC).rev());
    }

    @Test
    void 마지막_연결이_끊겨도_정해진_시간_안에_다시_붙으면_세션을_내리지_않는다() {
        FakeConnection a = join("A");
        sessions.leave(DOC, a);
        runAll();
        now = now.plusSeconds(30);
        join("B");
        now = now.plusSeconds(31);
        scheduled.forEach(Runnable::run);
        runAll();

        assertEquals(1, sessions.size());
    }

    @Test
    void 세션을_연_연결이_거부되어도_세션은_정해진_시간_뒤에_내려간다() {
        FakeConnection rejected = join("A", 3, PageRole.EDIT);
        assertEquals(CloseCodes.PROTOCOL, rejected.closedWith);
        sessions.leave(DOC, rejected);
        runAll();
        assertEquals(1, sessions.size());

        now = now.plusSeconds(61);
        scheduled.forEach(Runnable::run);
        runAll();

        assertEquals(0, sessions.size());
    }

    @Test
    void 문서를_불러오지_못해도_세션은_내려가고_다음_연결이_다시_불러온다() {
        store.failLoad = true;
        FakeConnection failed = join("A");
        assertEquals(1011, failed.closedWith);

        now = now.plusSeconds(61);
        scheduled.forEach(Runnable::run);
        runAll();
        assertEquals(0, sessions.size());

        store.failLoad = false;
        assertEquals(0, join("B").last(Welcome.class).rev());
    }

    @Test
    void 오래_떨어져_있던_클라이언트는_rebase_false_와_전체_문서를_받는다() {
        FakeConnection a = join("A");
        for (int i = 1; i <= 5; i++) {
            submit(a, i, i - 1, paragraph("B" + i, "가"));
            runAll();
        }

        // 리비전 차이가 한도(3)를 넘는다.
        Welcome farBehind = join("C", 1, PageRole.EDIT).last(Welcome.class);
        assertFalse(farBehind.rebase());
        assertNotNull(farBehind.snapshot());

        // 차이는 작지만 놓친 첫 묶음이 7일보다 오래전에 확정되었다.
        now = now.plus(Duration.ofDays(8));
        Welcome tooOld = join("D", 4, PageRole.EDIT).last(Welcome.class);
        assertFalse(tooOld.rebase());

        Welcome recent = join("E", 5, PageRole.EDIT).last(Welcome.class);
        assertTrue(recent.rebase());
        assertEquals(List.of(), recent.ops());
    }

    @Test
    void 리비전_0_에서_이어_붙는_클라이언트는_전체_문서가_아니라_놓친_묶음을_받는다() {
        FakeConnection a = join("A");
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();

        FakeConnection again = new FakeConnection("A-again");
        sessions.join(DOC, again, new Hello("client-A", 0, true), PageRole.EDIT);
        runAll();

        Welcome welcome = again.last(Welcome.class);
        assertNull(welcome.snapshot());
        assertEquals(1, welcome.ops().size());
        assertEquals("client-A", welcome.ops().get(0).clientId());
    }

    @Test
    void 서버에_없는_리비전으로_hello_하면_4008_로_끊는다() {
        FakeConnection a = join("A", 3, PageRole.EDIT);

        assertEquals(CloseCodes.PROTOCOL, a.closedWith);
        assertTrue(a.received.isEmpty());
    }

    @Test
    void 문서당_연결_한도를_넘으면_읽기_권한으로_받는다() {
        join("A");
        join("B");
        FakeConnection third = join("C");

        assertEquals(PageRole.VIEW, third.last(Welcome.class).role());
        submit(third, 1, 0, paragraph("X", "가"));
        runAll();
        assertInstanceOf(Nack.class, third.last());
    }

    @Test
    void 규칙에_걸린_묶음은_nack_하고_리비전을_올리지_않는다() {
        FakeConnection a = join("A");
        FakeConnection viewer = join("V", 0, PageRole.VIEW);
        submit(viewer, 1, 0, paragraph("X", "가"));
        submit(a, 1, 0, new InsertBlock("X", ROOT, "a", "spreadsheet", Attrs.NONE, RichText.EMPTY));
        submit(a, 2, 0, paragraph("Y", "가".repeat(101)));
        submit(a, 3, 0, new TextOp(ROOT, Delta.builder().retain(5).insert("!").build()));
        runAll();

        assertEquals(new Nack(1, "권한 VIEW 으로는 이 연산을 할 수 없다", 0), viewer.last());
        List<ServerMessage> replies = a.received.subList(1, a.received.size());
        assertEquals(3, replies.size());
        replies.forEach(reply -> assertInstanceOf(Nack.class, reply));
        assertTrue(((Nack) replies.get(1)).reason().contains("글자 수"));
        assertEquals(0, store.load(DOC).rev());
        assertTrue(store.appendSizes.isEmpty());
    }

    @Test
    void 같은_클라이언트가_새_연결로_붙어_있으면_예전_연결의_묶음은_새_연결에_remote_로_간다() {
        FakeConnection old = join("old");
        FakeConnection fresh = new FakeConnection("fresh");
        submit(old, 1, 0, paragraph("X", "가"));
        sessions.join(DOC, fresh, new Hello("client-old", 0, false), PageRole.EDIT);
        runAll();

        assertEquals(new Ack(1, 1), old.last());
        Remote remote = fresh.last(Remote.class);
        assertEquals("client-old", remote.change().clientId());
        assertEquals(1, remote.change().clientSeq());
    }
}
