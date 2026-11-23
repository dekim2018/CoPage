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
}
