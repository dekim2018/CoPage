package com.copage.ot;

import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.Map;
import java.util.Random;

/** 텍스트 테스트용 무작위 글과 편집. */
final class TextSamples {

    private static final String ALPHABET = "abc가나다 ";

    private TextSamples() {
    }

    static RichText randomText(Random random) {
        RichText.Builder builder = new RichText.Builder();
        int segments = random.nextInt(4);
        for (int i = 0; i < segments; i++) {
            builder.append(randomString(random, 4), random.nextBoolean() ? Attrs.NONE : randomAttrs(random, false));
        }
        return builder.build();
    }

    static Delta randomDelta(Random random, int length) {
        Delta.Builder delta = Delta.builder();
        int cursor = 0;
        int edits = 1 + random.nextInt(4);
        for (int i = 0; i < edits; i++) {
            int kind = random.nextInt(4);
            int remaining = length - cursor;
            if (kind == 0 || remaining == 0) {
                delta.insert(randomString(random, 3), random.nextInt(3) == 0 ? randomAttrs(random, false) : Attrs.NONE);
            } else {
                int count = 1 + random.nextInt(remaining);
                if (kind == 1) {
                    delta.retain(count);
                } else if (kind == 2) {
                    delta.retain(count, randomAttrs(random, true));
                } else {
                    delta.delete(count);
                }
                cursor += count;
            }
        }
        return delta.build();
    }

    private static Map<String, Object> randomAttrs(Random random, boolean allowRemove) {
        String key = random.nextBoolean() ? "bold" : "color";
        if (allowRemove && random.nextInt(3) == 0) {
            return Attrs.of(key, null);
        }
        return key.equals("bold") ? Attrs.of(key, true) : Attrs.of(key, random.nextBoolean() ? "red" : "blue");
    }

    private static String randomString(Random random, int maxLength) {
        int length = 1 + random.nextInt(maxLength);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
