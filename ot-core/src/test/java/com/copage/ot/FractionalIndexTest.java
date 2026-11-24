package com.copage.ot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.support.Iterations;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FractionalIndexTest {

    @Test
    void 맨_앞과_맨_뒤와_사이에_키를_만든다() {
        String first = FractionalIndex.between(null, null);
        String before = FractionalIndex.between(null, first);
        String after = FractionalIndex.between(first, null);
        String middle = FractionalIndex.between(first, after);

        assertTrue(before.compareTo(first) < 0);
        assertTrue(first.compareTo(middle) < 0);
        assertTrue(middle.compareTo(after) < 0);
    }

    @Test
    void 무작위_자리에_계속_끼워_넣어도_순서가_유지된다() {
        Random random = new Random(7);
        List<String> keys = new ArrayList<>();
        int inserts = Iterations.of(5_000);
        for (int i = 0; i < inserts; i++) {
            int at = random.nextInt(keys.size() + 1);
            String before = at == 0 ? null : keys.get(at - 1);
            String after = at == keys.size() ? null : keys.get(at);
            String key = FractionalIndex.between(before, after);

            assertTrue(before == null || before.compareTo(key) < 0, before + " < " + key);
            assertTrue(after == null || key.compareTo(after) < 0, key + " < " + after);
            assertFalse(key.endsWith("0"), "0 으로 끝나는 키: " + key);
            keys.add(at, key);
        }
    }

    @Test
    void 같은_자리에만_계속_넣어도_키가_만들어진다() {
        String low = FractionalIndex.between(null, null);
        String high = FractionalIndex.between(low, null);
        for (int i = 0; i < 200; i++) {
            String key = FractionalIndex.between(low, high);
            assertTrue(low.compareTo(key) < 0 && key.compareTo(high) < 0);
            high = key;
        }
    }

    /** 맨 뒤에 n 번 붙이고 가장 긴 키 길이를 돌려준다. 순서와 0 으로 끝나지 않는지도 본다. */
    private static int appendRun(String start, int n) {
        String last = start;
        int longest = start == null ? 0 : start.length();
        for (int i = 0; i < n; i++) {
            String key = FractionalIndex.between(last, null);
            assertTrue(last == null || last.compareTo(key) < 0, last + " < " + key);
            assertFalse(key.endsWith("0"), key);
            longest = Math.max(longest, key.length());
            last = key;
        }
        return longest;
    }

    private static int prependRun(String start, int n) {
        String first = start;
        int longest = start.length();
        for (int i = 0; i < n; i++) {
            String key = FractionalIndex.between(null, first);
            assertTrue(key.compareTo(first) < 0, key + " < " + first);
            assertFalse(key.endsWith("0"), key);
            longest = Math.max(longest, key.length());
            first = key;
        }
        return longest;
    }

    @Test
    void 맨_뒤에_계속_붙여도_키_길이는_붙인_횟수의_로그로만_자란다() {
        // 예전 방식은 1,000번에 167글자였다 (진단 DC-5).
        assertEquals(2, appendRun(null, 60));
        assertTrue(appendRun(null, 1_000) <= 3);
        assertTrue(appendRun(null, Iterations.of(100_000)) <= 5);
    }

    @Test
    void 맨_앞에_계속_넣어도_키_길이는_로그로만_자란다() {
        String first = FractionalIndex.between(null, null);
        assertTrue(prependRun(first, 1_000) <= 3);
        assertTrue(prependRun(first, Iterations.of(100_000)) <= 5);
    }

    @Test
    void 예전_방식으로_길어진_키_뒤와_앞에서도_짧게_이어_간다() {
        // 예전 방식으로 맨 뒤에 30번 붙인 키는 'zzzzz…' 꼴이다.
        String old = "zzzzzV";
        assertTrue(appendRun(old, 1_000) <= old.length() + 3);
        assertTrue(appendRun(old, Iterations.of(100_000)) <= old.length() + 5);
        String oldFirst = "00001";
        assertTrue(prependRun(oldFirst, 1_000) <= oldFirst.length() + 3);
        assertTrue(prependRun("z", 1_000) <= 4);
    }

    @Test
    void 붙이기와_끼워_넣기를_섞어도_순서와_모양이_유지된다() {
        // 붙이기, 앞에 넣기, 사이에 넣기를 고르게 섞는다. 무작위 자리 시험은 사이에 넣기가 대부분이다.
        Random random = new Random(11);
        List<String> keys = new ArrayList<>(List.of(FractionalIndex.between(null, null)));
        for (int i = 0; i < Iterations.of(20_000); i++) {
            int roll = random.nextInt(3);
            int at = roll == 0 ? keys.size() : roll == 1 ? 0 : random.nextInt(keys.size() + 1);
            String before = at == 0 ? null : keys.get(at - 1);
            String after = at == keys.size() ? null : keys.get(at);
            String key = FractionalIndex.between(before, after);
            assertTrue(before == null || before.compareTo(key) < 0, before + " < " + key);
            assertTrue(after == null || key.compareTo(after) < 0, key + " < " + after);
            assertFalse(key.endsWith("0"), key);
            keys.add(at, key);
        }
    }

    @Test
    void 순서가_뒤집힌_두_키는_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> FractionalIndex.between("V", "A"));
        assertThrows(IllegalArgumentException.class, () -> FractionalIndex.between("V", "V"));
    }
}
