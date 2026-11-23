package com.copage.ot;

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

    @Test
    void 순서가_뒤집힌_두_키는_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> FractionalIndex.between("V", "A"));
        assertThrows(IllegalArgumentException.class, () -> FractionalIndex.between("V", "V"));
    }
}
