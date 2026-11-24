package com.copage.collab.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.domain.PageRole;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChangePolicyTest {

    private final ChangePolicy policy = new ChangePolicy(3, 10);

    private static TextOp text(Delta delta) {
        return new TextOp("X", delta);
    }

    private Optional<String> check(PageRole role, Op... ops) {
        return policy.check(List.of(ops), role);
    }

    @Test
    void 읽기_권한은_어떤_편집도_못_한다() {
        assertTrue(check(PageRole.VIEW, text(Delta.builder().retain(1, Attrs.of("comment", "c1")).build())).isPresent());
    }

    @Test
    void 댓글_권한은_comment_서식만_바꾸는_retain_만_할_수_있다() {
        Delta addComment = Delta.builder().retain(2).retain(3, Attrs.of("comment", "c1")).build();
        Delta typeAndComment = Delta.builder().retain(3, Attrs.of("comment", "c1")).insert("!").build();
        Delta bold = Delta.builder().retain(3, Attrs.of("bold", true)).build();

        assertEquals(Optional.empty(), check(PageRole.COMMENT, text(addComment)));
        assertTrue(check(PageRole.COMMENT, text(typeAndComment)).isPresent());
        assertTrue(check(PageRole.COMMENT, text(bold)).isPresent());
        assertTrue(check(PageRole.COMMENT, new SetProps("X", "checked", true)).isPresent());
        assertEquals(Optional.empty(), check(PageRole.EDIT, text(bold)));
    }

    @Test
    void 블록_속성은_정해진_키와_값만_받는다() {
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "level", 2)));
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "level", 3L)));
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "checked", true)));
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "language", "c++")));
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "language", null)), "null 은 지우기다");
        for (Object[] bad : new Object[][] {
            {"level", 0}, {"level", 4}, {"level", "2"}, {"checked", "true"}, {"language", "<script>"},
            {"language", "a".repeat(33)}, {"onclick", "x"}, {"color", "red"}}) {
            assertTrue(check(PageRole.EDIT, new SetProps("X", (String) bad[0], bad[1])).isPresent(), bad[0] + "=" + bad[1]);
        }
        assertTrue(check(PageRole.EDIT, new InsertBlock("N", "r", "a", "heading", Attrs.of("level", 9), RichText.EMPTY))
                .isPresent());
        assertTrue(check(PageRole.EDIT, new SplitBlock("X", 0, "N", "r", "a", "todo", Attrs.of("checked", 1))).isPresent());
        assertEquals(Optional.empty(), check(PageRole.EDIT,
                new InsertBlock("N", "r", "a", "heading", Attrs.of("level", 2), RichText.EMPTY)));
    }

    @Test
    void 구분선에는_글을_넣을_수_없다() {
        Document withDivider = Document.create("r").apply(List.of(
                new InsertBlock("D", "r", "a", "divider", Attrs.NONE, RichText.EMPTY)));
        policy.checkSize(withDivider);

        Document withText = withDivider.apply(List.of(new TextOp("D", Delta.builder().insert("글").build())));
        assertThrows(InvalidOperationException.class, () -> policy.checkSize(withText));
        // 지운 구분선은 보지 않는다.
        policy.checkSize(withText.apply(List.of(new Op.DeleteBlock("D", true))));
    }

    @Test
    void 순서_키는_모양이_맞고_256_글자_이하여야_한다() {
        String longest = "a".repeat(ChangePolicy.MAX_POS_LENGTH);
        assertEquals(Optional.empty(), check(PageRole.EDIT, new InsertBlock("N", "r", longest, "paragraph", Attrs.NONE, RichText.EMPTY)));
        assertTrue(check(PageRole.EDIT, new InsertBlock("N", "r", longest + "a", "paragraph", Attrs.NONE, RichText.EMPTY)).isPresent());
        for (String bad : new String[] {"", "a0", "a-b", "가"}) {
            assertTrue(check(PageRole.EDIT, new InsertBlock("N", "r", bad, "paragraph", Attrs.NONE, RichText.EMPTY)).isPresent(), bad);
            assertTrue(check(PageRole.EDIT, new Op.MoveBlock("X", "r", bad)).isPresent(), bad);
            assertTrue(check(PageRole.EDIT, new SplitBlock("X", 0, "N", "r", bad, "paragraph", Attrs.NONE)).isPresent(), bad);
        }
        assertEquals(Optional.empty(), check(PageRole.EDIT, new Op.MoveBlock("X", "r", "V1")));
    }

    @Test
    void 허용하지_않는_블록_종류와_서식은_거부한다() {
        assertTrue(check(PageRole.EDIT, new InsertBlock("N", "r", "a", "iframe", Attrs.NONE, RichText.EMPTY)).isPresent());
        assertTrue(check(PageRole.EDIT, new SplitBlock("X", 0, "N", "r", "a", "page", Attrs.NONE)).isPresent());
        assertTrue(check(PageRole.EDIT, new SetProps("X", Op.TYPE_KEY, "script")).isPresent());
        assertTrue(check(PageRole.EDIT, text(Delta.builder().insert("a", Attrs.of("color", "red")).build())).isPresent());
        assertTrue(check(PageRole.EDIT,
                new InsertBlock("N", "r", "a", "paragraph", Attrs.NONE, RichText.of("a", Attrs.of("font", "x")))).isPresent());
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", Op.TYPE_KEY, "heading")));
    }

    @Test
    void 링크는_http_https_mailto_만_받는다() {
        for (String ok : List.of("https://example.com", "HTTP://a.b", "mailto:a@b.c")) {
            assertEquals(Optional.empty(), check(PageRole.EDIT, text(Delta.builder().insert("a", Attrs.of("link", ok)).build())), ok);
        }
        for (String bad : List.of("javascript:alert(1)", " javascript:x", "data:text/html,x", "ftp://a")) {
            assertTrue(check(PageRole.EDIT, text(Delta.builder().retain(1, Attrs.of("link", bad)).build())).isPresent(), bad);
        }
        // 링크를 지우는 것(null)은 허용한다.
        assertEquals(Optional.empty(), check(PageRole.EDIT, text(Delta.builder().retain(1, Attrs.of("link", null)).build())));
    }

    @Test
    void 문서_크기_한도를_넘으면_InvalidOperationException_을_던진다() {
        Document doc = Document.create("r");
        Document small = doc.apply(new InsertBlock("A", "r", "a", "paragraph", Attrs.NONE, RichText.of("0123456789")));
        policy.checkSize(small);

        assertThrows(InvalidOperationException.class, () -> policy.checkSize(small.apply(
                new TextOp("A", Delta.builder().insert("!").build()))));
        assertThrows(InvalidOperationException.class, () -> policy.checkSize(small.apply(List.of(
                new InsertBlock("B", "r", "b", "paragraph", Attrs.NONE, RichText.EMPTY),
                new InsertBlock("C", "r", "c", "paragraph", Attrs.NONE, RichText.EMPTY)))));
    }

    @Test
    void jsonb_에_그대로_저장할_수_없는_값은_거부한다() {
        String nul = "a" + (char) 0 + "b";
        String loneSurrogate = "a" + (char) 0xD83D;
        List<Op> rejected = List.of(
                text(Delta.builder().insert(nul).build()),
                text(Delta.builder().insert(loneSurrogate).build()),
                text(Delta.builder().insert("a", Attrs.of("link", "https://a" + nul)).build()),
                new InsertBlock(nul, "r", "a", "paragraph", Attrs.NONE, RichText.EMPTY),
                new InsertBlock("N", "r", "a", "paragraph", Attrs.NONE, RichText.of(nul)),
                new SplitBlock("X", 0, "N", "r", "a" + nul, "paragraph", Attrs.NONE),
                new SetProps("X", "level", 1e19),
                new SetProps("X", "level", 1.5),
                new SetProps("X", "level", List.of(1, 2.5)),
                new SetProps("X", "language", nul));
        for (Op op : rejected) {
            assertTrue(check(PageRole.EDIT, op).isPresent(), op.toString());
        }
    }

    @Test
    void 이모지와_long_범위_정수는_저장할_수_있다() {
        assertEquals(Optional.empty(), check(PageRole.EDIT, text(Delta.builder().insert("가😀").build())));
        // 저장할 수 있는지는 값의 종류만 본다. level 의 범위(1~3)는 속성 규칙이 따로 본다(블록_속성은_정해진_키와_값만_받는다).
        assertEquals(Optional.empty(), ChangePolicy.checkValue(Long.MAX_VALUE));
        assertTrue(check(PageRole.EDIT, new SetProps("X", "level", Long.MAX_VALUE)).isPresent());
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "level", 2)));
        assertEquals(Optional.empty(), check(PageRole.EDIT, new SetProps("X", "checked", null)));
    }
}
