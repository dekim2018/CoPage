package com.copage.ot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 설계 문서(docs/04-OT-DESIGN.md)의 변환 규칙을 사람이 읽을 수 있는 사례로 적은 테스트. */
class ConcurrentEditExamplesTest {

    /** 루트 아래에 문단 X("가나다라")와 Y("마바")가 차례로 있는 문서. */
    private static Document sample() {
        return Document.create("root")
                .apply(List.of(
                        new InsertBlock("X", "root", "A", "paragraph", Attrs.NONE, RichText.of("가나다라")),
                        new InsertBlock("Y", "root", "M", "paragraph", Attrs.NONE, RichText.of("마바"))));
    }

    private static SplitBlock splitX(int offset, String newId) {
        return new SplitBlock("X", offset, newId, "root", "D", "paragraph", Attrs.NONE);
    }

    private static TextOp insertAt(String blockId, int offset, String text) {
        return new TextOp(blockId, Delta.builder().retain(offset).insert(text).build());
    }

    /** a 가 나중에 확정된다고 보고 두 순서의 결과가 같은지 확인한 뒤 그 문서를 돌려준다. */
    private static Document converge(Document start, Op later, Op earlier) {
        Document viaEarlier = start.apply(earlier).apply(Transformer.transform(later, earlier, true));
        Document viaLater = start.apply(later).apply(Transformer.transform(earlier, later, false));
        assertEquals(viaEarlier, viaLater);
        return viaEarlier;
    }

    private static String text(Document doc, String blockId) {
        return doc.get(blockId).text().plainText();
    }

    @Test
    void Enter_뒤쪽에_치던_글자는_새_블록을_따라간다() {
        Document result = converge(sample(), insertAt("X", 3, "!"), splitX(2, "N"));

        assertEquals("가나", text(result, "X"));
        assertEquals("다!라", text(result, "N"));
    }

    @Test
    void Enter_자리에_정확히_친_글자는_앞_블록_끝에_남는다() {
        Document result = converge(sample(), insertAt("X", 2, "!"), splitX(2, "N"));

        assertEquals("가나!", text(result, "X"));
        assertEquals("다라", text(result, "N"));
    }

    @Test
    void Enter_위치를_걸쳐_지운_글자는_양쪽_블록에서_지워진다() {
        TextOp deleteMiddle = new TextOp("X", Delta.builder().retain(1).delete(2).build());

        Document result = converge(sample(), deleteMiddle, splitX(2, "N"));

        assertEquals("가", text(result, "X"));
        assertEquals("라", text(result, "N"));
    }

    @Test
    void Backspace로_합쳐진_블록에_치던_글자는_합쳐진_자리로_옮겨_간다() {
        Document result = converge(sample(), insertAt("Y", 1, "!"), new MergeBlock("Y", "X", 4));

        assertEquals("가나다라마!바", text(result, "X"));
        assertTrue(result.get("Y").deleted());
        assertEquals("", text(result, "Y"));
    }

    @Test
    void 합쳐질_대상_블록_끝에_친_글자는_두_글_사이에_들어간다() {
        Document result = converge(sample(), new MergeBlock("Y", "X", 4), insertAt("X", 4, "!"));

        assertEquals("가나다라!마바", text(result, "X"));
    }

    @Test
    void 두_사람이_다른_자리에서_Enter를_누르면_블록이_셋이_된다() {
        Document result = converge(sample(), splitX(3, "N2"), splitX(1, "N1"));

        assertEquals("가", text(result, "X"));
        assertEquals("나다", text(result, "N1"));
        assertEquals("라", text(result, "N2"));
    }

    @Test
    void 두_사람이_같은_자리에서_Enter를_누르면_먼저_확정된_쪽이_뒤쪽_글을_갖고_나중_쪽은_빈_줄이_된다() {
        Document result = converge(sample(), splitX(2, "LATER"), splitX(2, "EARLIER"));

        assertEquals("가나", text(result, "X"));
        assertEquals("다라", text(result, "EARLIER"));
        assertEquals("", text(result, "LATER"));
    }

    @Test
    void 나뉜_블록에_합치려던_블록은_뒤쪽_글을_가진_새_블록_끝에_붙는다() {
        Document result = converge(sample(), new MergeBlock("Y", "X", 4), splitX(1, "N"));

        assertEquals("가", text(result, "X"));
        assertEquals("나다라마바", text(result, "N"));
    }

    @Test
    void 합쳐진_블록을_나누려던_Enter는_합쳐진_자리에서_나눈다() {
        Op splitY = new SplitBlock("Y", 1, "N", "root", "P", "paragraph", Attrs.NONE);

        Document result = converge(sample(), splitY, new MergeBlock("Y", "X", 4));

        assertEquals("가나다라마", text(result, "X"));
        assertEquals("바", text(result, "N"));
    }

    @Test
    void 두_사람이_같은_Backspace를_누르면_한_번만_합쳐진다() {
        Op merge = new MergeBlock("Y", "X", 4);

        assertEquals(List.of(), Transformer.transform(merge, merge, true));
        assertEquals("가나다라마바", text(converge(sample(), merge, merge), "X"));
    }

    @Test
    void 삭제된_블록_안의_편집은_사라지지_않고_삭제를_되돌리면_함께_돌아온다() {
        Document start = sample();
        Op delete = new DeleteBlock("X", true);

        Document result = converge(start, insertAt("X", 4, "!"), delete);
        assertFalse(result.isVisible("X"));

        Document restored = result.apply(new DeleteBlock("X", false));
        assertEquals("가나다라!", text(restored, "X"));
        assertTrue(restored.isVisible("X"));
    }

    @Test
    void 같은_블록을_둘이_다른_곳으로_옮기면_나중에_확정된_이동이_남는다() {
        Document result =
                converge(sample(), new MoveBlock("Y", "X", "A"), new MoveBlock("Y", "root", "0V"));

        assertEquals("X", result.get("Y").parentId());
    }

    @Test
    void 같은_속성을_둘이_바꾸면_나중에_확정된_값이_남는다() {
        Document result =
                converge(sample(), new SetProps("X", Op.TYPE_KEY, "todo"), new SetProps("X", Op.TYPE_KEY, "heading"));

        assertEquals("todo", result.get("X").type());
    }

    @Test
    void 같은_자리에_넣은_두_블록은_순서_키가_같아도_모두에게_같은_순서로_보인다() {
        Op first = new InsertBlock("B2", "root", "D", "paragraph", Attrs.NONE, RichText.EMPTY);
        Op second = new InsertBlock("B1", "root", "D", "paragraph", Attrs.NONE, RichText.EMPTY);

        Document result = converge(sample(), first, second);

        assertEquals(
                List.of("X", "B1", "B2", "Y"),
                result.children("root").stream().map(b -> b.id()).toList());
    }

    @Test
    void 삭제된_블록을_나누거나_합치는_연산은_충돌로_거부한다() {
        Op delete = new DeleteBlock("X", true);

        assertThrows(TransformConflictException.class, () -> Transformer.transform(splitX(2, "N"), delete, true));
        assertThrows(TransformConflictException.class, () -> Transformer.transform(delete, splitX(2, "N"), true));
        assertThrows(
                TransformConflictException.class,
                () -> Transformer.transform(new MergeBlock("Y", "X", 4), delete, true));
    }

    @Test
    void 합쳐지는_블록_아래에_동시에_블록을_넣으면_충돌로_거부한다() {
        Op insertChild = new InsertBlock("C", "Y", "A", "paragraph", Attrs.NONE, RichText.EMPTY);
        Op merge = new MergeBlock("Y", "X", 4);

        assertThrows(TransformConflictException.class, () -> Transformer.transform(insertChild, merge, true));
        assertThrows(TransformConflictException.class, () -> Transformer.transform(merge, insertChild, true));
    }

    @Test
    void 두_블록을_서로의_아래로_옮기면_나중_것은_적용할_수_없다() {
        Document start = sample();
        Op xUnderY = new MoveBlock("X", "Y", "A");
        Op yUnderX = new MoveBlock("Y", "X", "A");

        List<Op> transformed = Transformer.transform(yUnderX, xUnderY, true);

        assertThrows(InvalidOperationException.class, () -> start.apply(xUnderY).apply(transformed));
    }

    @Test
    void 묶음_안의_연산_하나가_실패하면_문서는_그대로다() {
        Document start = sample();
        List<Op> change = List.of(insertAt("X", 0, "!"), new DeleteBlock("없는블록", true));

        assertThrows(InvalidOperationException.class, () -> start.apply(change));
        assertEquals("가나다라", text(start, "X"));
    }

    @Test
    void 루트_블록은_지우거나_옮기거나_나눌_수_없다() {
        Document start = sample();

        assertThrows(InvalidOperationException.class, () -> start.apply(new DeleteBlock("root", true)));
        assertThrows(InvalidOperationException.class, () -> start.apply(new MoveBlock("root", "X", "A")));
        assertThrows(
                InvalidOperationException.class,
                () -> start.apply(new SplitBlock("root", 0, "N", "root", "A", "paragraph", Attrs.NONE)));
    }

    @Test
    void 자식이_남아_있는_블록은_합칠_수_없다() {
        Document start = sample()
                .apply(new InsertBlock("C", "Y", "A", "paragraph", Attrs.NONE, RichText.of("자식")));

        assertThrows(InvalidOperationException.class, () -> start.apply(new MergeBlock("Y", "X", 4)));
    }

    @Test
    void 이어서_친_글자는_보내기_전에_연산_하나로_줄어든다() {
        List<Op> buffer = List.of();
        buffer = Changes.append(buffer, List.of(insertAt("X", 4, "안")));
        buffer = Changes.append(buffer, List.of(insertAt("X", 5, "녕")));
        buffer = Changes.append(buffer, List.of(insertAt("X", 6, "!")));

        assertEquals(List.of(insertAt("X", 4, "안녕!")), buffer);
    }
}
