package com.copage.ot.text;

import java.util.Map;

/** 같은 서식이 이어지는 글자 구간. */
public record Segment(String text, Map<String, Object> attrs) {

    public Segment {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("빈 구간은 만들 수 없다");
        }
        attrs = Attrs.merge(Attrs.NONE, Attrs.copy(attrs), false);
    }

    public int length() {
        return text.length();
    }
}
