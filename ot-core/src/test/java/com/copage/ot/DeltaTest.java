package com.copage.ot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copage.ot.support.Iterations;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DeltaTest {

    @Test
    void 글자를_넣고_지우고_서식을_바꾼다() {
        RichText text = RichText.of("배포 일정 확정");
        Delta delta = Delta.builder()
                .retain(5)
                .insert(" 초안")
                .retain(1)
                .retain(2, Attrs.of("bold", true))
                .build();

        RichText result = delta.apply(text);

        assertEquals("배포 일정 초안 확정", result.plainText());
        assertEquals(Attrs.of("bold", true), result.segments().get(1).attrs());
        assertEquals("확정", result.segments().get(1).text());
    }

    @Test
    void 같은_편집은_적는_순서가_달라도_같은_값이_된다() {
        Delta insertFirst = Delta.builder().retain(2).insert("x").delete(1).build();
        Delta deleteFirst = Delta.builder().retain(2).delete(1).insert("x").build();
        Delta withTrailingRetain = Delta.builder().retain(2).insert("x").delete(1).retain(3).build();

        assertEquals(insertFirst, deleteFirst);
        assertEquals(insertFirst, withTrailingRetain);
    }

    @Test
    void 같은_자리에_둘_다_넣으면_먼저_확정된_글자가_앞에_온다() {
        RichText text = RichText.of("가나");
        Delta earlier = Delta.builder().retain(1).insert("A").build();
        Delta later = Delta.builder().retain(1).insert("B").build();

        RichText viaEarlier = later.transform(earlier, true).apply(earlier.apply(text));
        RichText viaLater = earlier.transform(later, false).apply(later.apply(text));

        assertEquals("가AB나", viaEarlier.plainText());
        assertEquals(viaEarlier, viaLater);
    }

    @Test
    void 같은_글자의_같은_서식을_둘_다_바꾸면_나중에_확정된_값이_남는다() {
        RichText text = RichText.of("가나다");
        Delta earlier = Delta.builder().retain(3, Attrs.of("color", "red")).build();
        Delta later = Delta.builder().retain(3, Attrs.of("color", "blue")).build();

        RichText viaEarlier = later.transform(earlier, true).apply(earlier.apply(text));
        RichText viaLater = earlier.transform(later, false).apply(later.apply(text));

        assertEquals(Attrs.of("color", "blue"), viaEarlier.segments().get(0).attrs());
        assertEquals(viaEarlier, viaLater);
    }

    @Test
    void 글_길이를_넘는_편집은_거부한다() {
        Delta delta = Delta.builder().retain(3).delete(2).build();
        assertThrows(IllegalArgumentException.class, () -> delta.apply(RichText.of("가나다")));
    }

    @Test
    void 이모지의_가운데를_자르는_편집은_거부한다() {
        RichText text = RichText.of("가😀나");
        Delta cutInside = Delta.builder().retain(2).insert("x").build();
        Delta cutAfter = Delta.builder().retain(3).insert("x").build();

        assertThrows(IllegalArgumentException.class, () -> cutInside.apply(text));
        assertEquals("가😀x나", cutAfter.apply(text).plainText());
    }

    @Test
    void 무작위_두_편집은_어느_순서로_적용해도_같은_글이_된다() {
        int runs = Iterations.of(20_000);
        for (int i = 0; i < runs; i++) {
            Random random = new Random(1_000L + i);
            RichText text = TextSamples.randomText(random);
            Delta a = TextSamples.randomDelta(random, text.length());
            Delta b = TextSamples.randomDelta(random, text.length());

            RichText left = b.transform(a, false).apply(a.apply(text));
            RichText right = a.transform(b, true).apply(b.apply(text));

            assertEquals(left, right, "시드 " + (1_000L + i) + " 글 " + text + " a " + a + " b " + b);
        }
    }

    @Test
    void 합성한_편집은_차례로_적용한_것과_같다() {
        int runs = Iterations.of(20_000);
        for (int i = 0; i < runs; i++) {
            Random random = new Random(2_000L + i);
            RichText text = TextSamples.randomText(random);
            Delta first = TextSamples.randomDelta(random, text.length());
            RichText middle = first.apply(text);
            Delta second = TextSamples.randomDelta(random, middle.length());

            assertEquals(
                    second.apply(middle),
                    first.compose(second).apply(text),
                    "시드 " + (2_000L + i) + " 글 " + text + " 첫째 " + first + " 둘째 " + second);
        }
    }

    @Test
    void 역연산을_적용하면_원래_글로_돌아온다() {
        int runs = Iterations.of(20_000);
        for (int i = 0; i < runs; i++) {
            Random random = new Random(3_000L + i);
            RichText text = TextSamples.randomText(random);
            Delta delta = TextSamples.randomDelta(random, text.length());

            RichText restored = delta.invert(text).apply(delta.apply(text));

            assertEquals(text, restored, "시드 " + (3_000L + i) + " 글 " + text + " 편집 " + delta);
        }
    }

    @Test
    void 글을_나누는_위치와_편집을_가르는_규칙이_서로_맞는다() {
        int runs = Iterations.of(20_000);
        for (int i = 0; i < runs; i++) {
            Random random = new Random(4_000L + i);
            RichText text = TextSamples.randomText(random);
            Delta delta = TextSamples.randomDelta(random, text.length());
            int offset = random.nextInt(text.length() + 1);

            // 편집을 먼저 하고 옮겨진 위치에서 나눈 결과
            RichText edited = delta.apply(text);
            int moved = delta.transformPosition(offset);
            // 먼저 나눈 뒤 갈라진 편집을 각각 적용한 결과
            Delta[] parts = delta.splitAt(offset);
            RichText head = parts[0].apply(text.slice(0, offset));
            RichText tail = parts[1].apply(text.slice(offset, text.length()));

            String message = "시드 " + (4_000L + i) + " 글 " + text + " 편집 " + delta + " 위치 " + offset;
            assertEquals(edited.slice(0, moved), head, message);
            assertEquals(edited.slice(moved, edited.length()), tail, message);
        }
    }
}
