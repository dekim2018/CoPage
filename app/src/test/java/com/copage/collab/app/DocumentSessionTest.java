package com.copage.collab.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.Cursor;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.Peer;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.ServerMessage.Ack;
import com.copage.collab.domain.ServerMessage.Nack;
import com.copage.collab.domain.ServerMessage.Remote;
import com.copage.collab.domain.ServerMessage.Welcome;
import com.copage.collab.domain.StoredChange;
import com.copage.collab.out.InMemoryDocumentStore;
import com.copage.ot.model.Document;
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
import java.util.Map;
import java.util.Optional;
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
                new DocumentSession.Settings(maxConnections, 3, Duration.ofDays(7), Duration.ofSeconds(60), 3, 3),
                tasks::add,
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
        public void saveSnapshot(UUID docId, Document document, long rev) {
            delegate.saveSnapshot(docId, document, rev);
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
        sessions.join(DOC, connection, new Hello("client-" + id, lastRev, lastRev > 0), role, "이름-" + connection.id());
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
        assertEquals(List.of(new Ack(1, 1), new Ack(2, 2)), a.sync.subList(1, 3));
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
        assertEquals(new Ack(1, 1), a.sync.get(1));
        assertEquals(new Ack(1, 2), b.sync.get(2));
    }

    @Test
    void 커밋에_실패하면_ack_를_보내지_않고_세션을_버린다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        store.fail = true;
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();

        assertEquals(1, a.sync.size(), "welcome 말고는 아무것도 받지 않았다");
        assertEquals(1, b.sync.size());
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
        sessions.join(DOC, again, new Hello("client-A", 1, true), PageRole.EDIT, "이름-" + again.id());
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
        sessions.join(DOC, again, new Hello("client-A", 1, true), PageRole.EDIT, "이름-" + again.id());
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
    void 마지막_스냅샷_이후_묶음이_간격만큼_쌓이면_스냅샷을_남긴다() {
        FakeConnection a = join("A");
        for (int i = 1; i <= 7; i++) {
            submit(a, i, i - 1, paragraph("B" + i, "가"));
            runAll();
        }

        assertEquals(List.of(3L, 6L), store.delegate.snapshotRevs(DOC));
    }

    @Test
    void 세션을_내릴_때_마지막_스냅샷_이후_묶음이_있으면_스냅샷을_남긴다() {
        FakeConnection a = join("A");
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();
        sessions.leave(DOC, a);
        runAll();
        now = now.plusSeconds(61);
        scheduled.forEach(Runnable::run);
        runAll();

        assertEquals(List.of(1L), store.delegate.snapshotRevs(DOC));

        // 다시 올라온 세션은 그 스냅샷부터 센다. 묶음 두 개로는 아직 다음 스냅샷을 남기지 않는다.
        FakeConnection again = join("B");
        submit(again, 1, 1, paragraph("Y", "나"));
        submit(again, 2, 1, paragraph("Z", "다"));
        runAll();
        assertEquals(List.of(1L), store.delegate.snapshotRevs(DOC));
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
        sessions.join(DOC, again, new Hello("client-A", 0, true), PageRole.EDIT, "이름-" + again.id());
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

    /** userId 가 users 에 있으면 그 등급, 없으면 권한 없음. 물어본 사용자를 asked 에 남긴다. */
    private DocumentSession.RoleResolver resolver(Map<UUID, PageRole> roles, List<UUID> asked) {
        return (userId, docId) -> {
            assertEquals(DOC, docId);
            asked.add(userId);
            return Optional.ofNullable(roles.get(userId));
        };
    }

    @Test
    void 권한이_내려가면_그_연결에만_role_을_보내고_이후_묶음은_새_등급으로_검사한다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        List<UUID> asked = new ArrayList<>();

        sessions.reauthorize(doc -> true, a.userId()::equals, resolver(Map.of(a.userId(), PageRole.VIEW), asked));
        runAll();

        assertEquals(List.of(a.userId()), asked, "다른 사용자는 다시 계산하지 않는다");
        assertEquals(new ServerMessage.Role(PageRole.VIEW), a.last());
        assertFalse(b.sync.stream().anyMatch(ServerMessage.Role.class::isInstance));
        submit(a, 1, 0, paragraph("X", "가"));
        runAll();
        assertInstanceOf(Nack.class, a.last());
        assertNull(a.closedWith);
    }

    @Test
    void 권한을_잃으면_4003_으로_닫고_등급이_같으면_아무것도_보내지_않는다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        int before = b.received.size();

        sessions.reauthorize(doc -> true, user -> true, resolver(Map.of(b.userId(), PageRole.EDIT), new ArrayList<>()));
        runAll();

        assertEquals(CloseCodes.FORBIDDEN, a.closedWith);
        assertNull(b.closedWith);
        assertEquals(before, b.received.size());
    }

    @Test
    void 연결_한도로_읽기로_받은_연결은_다시_계산해도_읽기_위로_올리지_않는다() {
        join("A");
        join("B");
        FakeConnection third = join("C");
        int before = third.received.size();

        sessions.reauthorize(doc -> true, third.userId()::equals,
                resolver(Map.of(third.userId(), PageRole.FULL), new ArrayList<>()));
        runAll();

        assertEquals(before, third.received.size(), "읽기 그대로라 role 을 보내지 않는다");
    }

    @Test
    void 다른_문서나_계산에_실패하면_바꾸지_않는다() {
        FakeConnection a = join("A");
        int before = a.received.size();

        sessions.reauthorize(doc -> false, user -> true, resolver(Map.of(), new ArrayList<>()));
        sessions.reauthorize(doc -> true, user -> true, (userId, docId) -> {
            throw new IllegalStateException("DB 가 끊겼다");
        });
        runAll();

        assertEquals(before, a.received.size());
        assertNull(a.closedWith);
    }

    @Test
    void 기록_창_밖의_순번을_다시_보내면_저장소로_걸러_처음_리비전으로_ack_한다() {
        FakeConnection a = join("A");
        submit(a, 1, 0, paragraph("X", "가"));
        for (int seq = 2; seq <= 5; seq++) {
            submit(a, seq, seq - 1, type("X", 0, "나"));
        }
        runAll();

        // 기록 창은 3 이다. 순번 1 은 이미 창 밖이다.
        submit(a, 1, 4, paragraph("X", "가"));
        runAll();

        assertEquals(new Ack(1, 1), a.last());
        assertEquals(5, store.load(DOC).rev());
    }

    @Test
    void 기록_창보다_오래된_기준_리비전의_묶음을_보낸_연결은_4011_로_닫아_재접속하게_한다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        submit(a, 1, 0, paragraph("X", "가"));
        for (int seq = 2; seq <= 5; seq++) {
            submit(a, seq, seq - 1, type("X", 0, "나"));
        }
        runAll();

        submit(b, 1, 1, type("X", 0, "다"));
        runAll();

        // nack 하면 클라이언트가 묶음을 버리므로 보내지 않는다.
        assertFalse(b.sync.stream().anyMatch(Nack.class::isInstance));
        assertEquals(CloseCodes.SLOW, b.closedWith);
        assertEquals(5, store.load(DOC).rev());
        assertNull(a.closedWith);
    }

    @Test
    void 기록_창은_1_이상이어야_한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSession.Settings(2, 3, Duration.ofDays(7), Duration.ofSeconds(60), 3, 0));
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
        List<ServerMessage> replies = a.sync.subList(1, a.sync.size());
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
        sessions.join(DOC, fresh, new Hello("client-old", 0, false), PageRole.EDIT, "이름-" + fresh.id());
        runAll();

        assertEquals(new Ack(1, 1), old.last());
        Remote remote = fresh.last(Remote.class);
        assertEquals("client-old", remote.change().clientId());
        assertEquals(1, remote.change().clientSeq());
    }

    @Test
    void 들어오면_다른_연결에_알리고_새_연결은_기존_연결과_커서를_peers_로_받는다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");

        Welcome welcome = b.received.get(0) instanceof Welcome w ? w : null;
        assertNotNull(welcome);
        assertEquals(List.of("client-A"), welcome.peers().stream().map(Peer::clientId).toList());
        assertEquals("이름-A", welcome.peers().get(0).name());
        ServerMessage.Presence entered = a.last(ServerMessage.Presence.class);
        assertEquals("client-B", entered.peer().clientId());
        assertFalse(entered.left());
        assertNull(entered.peer().cursor());

        Cursor cursor = new Cursor(new Cursor.Position("X", 1), new Cursor.Position("X", 3));
        sessions.presence(DOC, a, cursor);
        runAll();
        assertEquals(cursor, b.last(ServerMessage.Presence.class).peer().cursor());
        assertInstanceOf(ServerMessage.Presence.class, a.last(), "자기 커서는 자기에게 돌아오지 않는다");
        assertEquals("client-B", a.last(ServerMessage.Presence.class).peer().clientId());

        FakeConnection c = join("C");
        Welcome third = (Welcome) c.received.get(0);
        assertEquals(cursor, third.peers().stream().filter(p -> p.clientId().equals("client-A")).findFirst().orElseThrow().cursor());
    }

    @Test
    void 나가면_남은_연결에_퇴장을_알린다() {
        FakeConnection a = join("A");
        FakeConnection b = join("B");
        sessions.leave(DOC, a);
        runAll();

        ServerMessage.Presence left = b.last(ServerMessage.Presence.class);
        assertTrue(left.left());
        assertEquals("client-A", left.peer().clientId());
    }

    @Test
    void 같은_사람은_어느_문서에서나_같은_색이다() {
        UUID user = UUID.randomUUID();
        assertEquals(Peer.colorOf(user), Peer.colorOf(UUID.fromString(user.toString())));
        assertTrue(Peer.COLORS.contains(Peer.colorOf(user)));
        assertEquals(12, Peer.COLORS.size());
    }
}
