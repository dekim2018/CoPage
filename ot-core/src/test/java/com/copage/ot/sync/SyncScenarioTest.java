package com.copage.ot.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 서버와 클라이언트가 주고받는 순서를 손으로 짠 사례. docs/01-PLAN.md 3장의 흐름과 docs/05-PROTOCOL.md 의 재접속 규칙. */
class SyncScenarioTest {

    private static Document sample() {
        return Document.create("root")
                .apply(List.of(
                        new InsertBlock("X", "root", "A", "paragraph", Attrs.NONE, RichText.of("본문")),
                        new InsertBlock("Y", "root", "M", "paragraph", Attrs.NONE, RichText.of("둘째"))));
    }

    private static List<Op> type(String blockId, int offset, String text) {
        return List.of(new TextOp(blockId, Delta.builder().retain(offset).insert(text).build()));
    }

    private static CommittedChange accepted(SubmitResult result) {
        return ((SubmitResult.Accepted) result).change();
    }

    @Test
    void 두_사람이_같은_문단의_앞과_끝을_동시에_고치면_둘_다_반영된다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 120);
        ClientSession a = new ClientSession("A", start, 120);
        ClientSession b = new ClientSession("B", start, 120);

        a.edit(type("X", 0, "안녕 "));
        b.edit(type("X", 2, " 끝"));
        Submission fromA = a.poll().orElseThrow();
        Submission fromB = b.poll().orElseThrow();
        assertEquals("안녕 본문", a.view().get("X").text().plainText());
        assertEquals("본문 끝", b.view().get("X").text().plainText());

        CommittedChange first = accepted(server.submit(fromA));
        CommittedChange second = accepted(server.submit(fromB));
        assertEquals(121, first.rev());
        assertEquals(122, second.rev());

        a.onAck(fromA.clientSeq(), first.rev());
        a.onChange(second);
        b.onChange(first);
        b.onAck(fromB.clientSeq(), second.rev());

        assertEquals("안녕 본문 끝", server.document().get("X").text().plainText());
        assertEquals(server.document(), a.view());
        assertEquals(server.document(), b.view());
        assertFalse(a.hasPending());
        assertFalse(b.hasPending());
    }

    @Test
    void 답을_기다리는_동안_한_편집은_모아_두었다가_ack_뒤에_한_묶음으로_보낸다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);

        a.edit(type("X", 2, "1"));
        Submission first = a.poll().orElseThrow();
        a.edit(type("X", 3, "2"));
        a.edit(type("X", 4, "3"));
        assertEquals(Optional.empty(), a.poll());

        a.onAck(first.clientSeq(), accepted(server.submit(first)).rev());
        Submission second = a.poll().orElseThrow();

        assertEquals(type("X", 3, "23"), second.ops());
        assertEquals(1, second.baseRev());
        a.onAck(second.clientSeq(), accepted(server.submit(second)).rev());
        assertEquals("본문123", server.document().get("X").text().plainText());
        assertEquals(server.document(), a.view());
    }

    @Test
    void 같은_묶음이_두_번_도착해도_한_번만_적용된다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        a.edit(type("X", 2, "!"));
        Submission submission = a.poll().orElseThrow();

        SubmitResult first = server.submit(submission);
        SubmitResult again = server.submit(submission);

        assertEquals(new SubmitResult.Duplicate(1), again);
        assertEquals(1, accepted(first).rev());
        assertEquals(1, server.rev());
        assertEquals("본문!", server.document().get("X").text().plainText());
    }

    @Test
    void 보낸_묶음의_ack를_못_받고_끊겼다_돌아오면_놓친_기록에서_내_묶음을_알아본다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        ClientSession b = new ClientSession("B", start, 0);

        a.edit(type("X", 2, "!"));
        Submission fromA = a.poll().orElseThrow();
        server.submit(fromA);
        // A 는 ack 를 받지 못한 채 끊겼다. 그 사이 B 가 편집했고 A 도 오프라인에서 더 편집했다.
        b.onChange(server.changesSince(0).get(0));
        b.edit(type("Y", 0, "B "));
        server.submit(b.poll().orElseThrow());
        a.edit(type("X", 3, "?"));

        Optional<Submission> next = a.resync(server.changesSince(a.confirmedRev()));

        assertTrue(next.isPresent(), "오프라인에서 한 편집을 보내야 한다");
        assertEquals(2, next.get().baseRev());
        assertEquals(type("X", 3, "?"), next.get().ops());
        a.onAck(next.get().clientSeq(), accepted(server.submit(next.get())).rev());
        assertEquals("본문!?", server.document().get("X").text().plainText());
        assertEquals("B 둘째", server.document().get("Y").text().plainText());
        assertEquals(server.document(), a.view());
    }

    @Test
    void 보낸_묶음이_서버에_닿지_못하고_끊겼다_돌아오면_같은_순번으로_다시_보낸다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        ClientSession b = new ClientSession("B", start, 0);

        a.edit(type("X", 2, "!"));
        Submission lost = a.poll().orElseThrow();
        b.edit(type("X", 0, "B "));
        server.submit(b.poll().orElseThrow());

        Submission resent = a.resync(server.changesSince(a.confirmedRev())).orElseThrow();

        assertEquals(lost.clientSeq(), resent.clientSeq());
        assertEquals(1, resent.baseRev());
        assertEquals(type("X", 4, "!"), resent.ops());
        a.onAck(resent.clientSeq(), accepted(server.submit(resent)).rev());
        assertEquals("B 본문!", server.document().get("X").text().plainText());
        assertEquals(server.document(), a.view());
    }

    @Test
    void 변환으로_풀_수_없는_충돌이면_서버는_거부하고_클라이언트는_확정_문서로_돌아간다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        ClientSession b = new ClientSession("B", start, 0);

        // A 는 Y 를 X 에 합치고, 동시에 B 는 Y 를 지운다.
        a.edit(List.of(new MergeBlock("Y", "X", 2)));
        a.edit(type("X", 4, " 이어서"));
        Submission fromA = a.poll().orElseThrow();
        b.edit(List.of(new DeleteBlock("Y", true)));
        CommittedChange deleted = accepted(server.submit(b.poll().orElseThrow()));

        SubmitResult result = server.submit(fromA);
        assertTrue(result instanceof SubmitResult.Rejected, "삭제된 블록의 합치기는 거부해야 한다");

        // A 는 nack 가 오기 전에, B 의 삭제를 받는 순간 이미 화면을 되돌린다.
        a.onChange(deleted);
        assertEquals(server.document(), a.view());
        a.onNack(fromA.clientSeq());
        assertFalse(a.hasPending());
        assertEquals("본문", a.view().get("X").text().plainText());
    }

    @Test
    void 지금은_적용할_수_없는_묶음도_서버의_판정이_올_때까지_버리지_않는다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        ClientSession b = new ClientSession("B", start, 0);

        // A 는 X 를 Y 아래로 옮긴다. B 는 Y 를 X 아래로 옮겼다가 다시 루트로 돌려놓는다.
        a.edit(List.of(new MoveBlock("X", "Y", "A")));
        Submission fromA = a.poll().orElseThrow();
        b.edit(List.of(new MoveBlock("Y", "X", "A")));
        CommittedChange under = accepted(server.submit(b.poll().orElseThrow()));
        b.onAck(1, under.rev());
        b.edit(List.of(new MoveBlock("Y", "root", "M")));
        CommittedChange back = accepted(server.submit(b.poll().orElseThrow()));

        // 첫 이동만 받은 시점에는 A 의 묶음이 순환을 만들어 화면에서 빠진다.
        a.onChange(under);
        assertEquals("X", a.view().get("Y").parentId());
        assertEquals("root", a.view().get("X").parentId());
        a.onChange(back);

        // 서버에는 B 의 두 이동이 모두 끝난 뒤에 도착했으므로 받아들여진다.
        CommittedChange moved = accepted(server.submit(fromA));
        a.onAck(fromA.clientSeq(), moved.rev());

        assertEquals("Y", server.document().get("X").parentId());
        assertEquals(server.document(), a.view());
        assertEquals(server.document(), a.confirmed());
    }

    @Test
    void 두_사람이_같은_합치기를_하면_나중_사람의_묶음은_리비전을_올리지_않고_확인만_받는다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        ClientSession a = new ClientSession("A", start, 0);
        ClientSession b = new ClientSession("B", start, 0);
        List<Op> merge = List.of(new MergeBlock("Y", "X", 2));

        a.edit(merge);
        b.edit(merge);
        Submission fromA = a.poll().orElseThrow();
        Submission fromB = b.poll().orElseThrow();
        CommittedChange first = accepted(server.submit(fromA));
        SubmitResult second = server.submit(fromB);

        assertEquals(new SubmitResult.Duplicate(1), second);
        b.onChange(first);
        b.onAck(fromB.clientSeq(), 1);
        assertEquals(1, b.confirmedRev());
        assertEquals(server.document(), b.view());
        assertFalse(b.hasPending());
    }
}
