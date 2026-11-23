package com.copage.ot.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
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

    @Test
    void 결과_문서_검사에_걸린_묶음은_거부하고_상태를_바꾸지_않는다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0);
        Submission tooBig = new Submission("A", 1, 0, type("X", 0, "가나다"));

        SubmitResult result = server.submit(tooBig, doc -> {
            if (doc.get("X").text().length() > 4) {
                throw new InvalidOperationException("글이 너무 길다");
            }
        });

        assertEquals(new SubmitResult.Rejected("글이 너무 길다"), result);
        assertEquals(0, server.rev());
        assertEquals(start, server.document());
        // 거부된 순번은 기록하지 않으므로 같은 순번으로 다시 보내면 다시 판정한다.
        assertTrue(server.submit(tooBig) instanceof SubmitResult.Accepted);
    }

    @Test
    void 기록_창보다_오래된_확정_기록은_버리고_순번도_잊는다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 10, 3);
        for (int seq = 1; seq <= 5; seq++) {
            accepted(server.submit(new Submission("A", seq, server.rev(), type("X", 0, "가"))));
        }

        assertEquals(15, server.rev());
        assertEquals(3, server.historySize());
        assertEquals(12, server.oldestBaseRev());
        assertEquals(List.of(13L, 14L, 15L), server.changesSince(12).stream().map(CommittedChange::rev).toList());
        assertEquals(List.of(15L), server.changesSince(14).stream().map(CommittedChange::rev).toList());
        assertEquals(List.of(), server.changesSince(15));
        // 창 안의 순번은 알아본다.
        assertEquals(new SubmitResult.Duplicate(15), server.submit(new Submission("A", 5, 14, type("X", 0, "가"))));
        // 창 밖의 순번은 잊었으므로 저장소가 걸러야 한다. 여기서는 기준 리비전이 창 안이면 새 묶음으로 본다.
        assertTrue(server.submit(new Submission("A", 1, 15, type("X", 0, "가"))) instanceof SubmitResult.Accepted);
    }

    @Test
    void 기록_창보다_오래된_기준_리비전의_묶음은_Stale_로_돌려주고_상태를_바꾸지_않는다() {
        Document start = sample();
        ServerDocument server = new ServerDocument(start, 0, 2);
        for (int seq = 1; seq <= 3; seq++) {
            accepted(server.submit(new Submission("A", seq, server.rev(), type("X", 0, "가"))));
        }
        Document before = server.document();

        SubmitResult stale = server.submit(new Submission("B", 1, 0, type("X", 0, "B")));

        assertEquals(new SubmitResult.Stale(1), stale);
        assertEquals(3, server.rev());
        assertEquals(before, server.document());
        // 창의 첫 리비전을 기준으로 한 묶음은 받는다.
        assertTrue(server.submit(new Submission("B", 1, 1, type("X", 0, "B"))) instanceof SubmitResult.Accepted);
    }

    @Test
    void 아직_없는_기준_리비전의_묶음은_거부한다() {
        ServerDocument server = new ServerDocument(sample(), 0, 2);

        assertTrue(server.submit(new Submission("A", 1, 1, type("X", 0, "가"))) instanceof SubmitResult.Rejected);
    }

    @Test
    void 블록_목록으로_만든_문서는_원래_문서와_같다() {
        Document start = sample().apply(new DeleteBlock("Y", true));
        List<Block> blocks = new ArrayList<>();
        start.blocks().forEach(blocks::add);

        assertEquals(start, Document.of("root", blocks));
    }
}
