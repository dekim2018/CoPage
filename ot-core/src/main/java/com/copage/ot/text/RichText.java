package com.copage.ot.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 서식이 있는 글. 바뀌지 않는 값이다.
 *
 * <p>글자 위치는 UTF-16 코드 단위로 센다. JavaScript 문자열과 같은 기준이라 서버와 브라우저가 같은 숫자를 쓴다.
 */
public final class RichText {

    public static final RichText EMPTY = new RichText(List.of());

    private final List<Segment> segments;
    private final int length;
    private String plain;

    private RichText(List<Segment> normalized) {
        this.segments = normalized;
        int total = 0;
        for (Segment segment : normalized) {
            total += segment.length();
        }
        this.length = total;
    }

    public static RichText of(String text) {
        return of(text, Attrs.NONE);
    }

    public static RichText of(String text, Map<String, Object> attrs) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return new RichText(List.of(new Segment(text, attrs)));
    }

    public static RichText ofSegments(List<Segment> segments) {
        Builder builder = new Builder();
        for (Segment segment : segments) {
            builder.append(segment.text(), segment.attrs());
        }
        return builder.build();
    }

    public List<Segment> segments() {
        return segments;
    }

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public String plainText() {
        if (plain == null) {
            StringBuilder sb = new StringBuilder(length);
            for (Segment segment : segments) {
                sb.append(segment.text());
            }
            plain = sb.toString();
        }
        return plain;
    }

    /** offset 이 이모지처럼 코드 단위 2개를 쓰는 글자의 가운데면 true. 이런 위치는 자를 수 없다. */
    public boolean splitsSurrogatePair(int offset) {
        if (offset <= 0 || offset >= length) {
            return false;
        }
        String text = plainText();
        return Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }

    /** [from, to) 구간을 잘라 낸다. */
    public RichText slice(int from, int to) {
        if (from < 0 || to > length || from > to) {
            throw new IndexOutOfBoundsException("구간 [" + from + ", " + to + ") 이 길이 " + length + " 를 벗어난다");
        }
        if (from == to) {
            return EMPTY;
        }
        if (from == 0 && to == length) {
            return this;
        }
        List<Segment> out = new ArrayList<>();
        int cursor = 0;
        for (Segment segment : segments) {
            int end = cursor + segment.length();
            if (end > from && cursor < to) {
                int start = Math.max(from, cursor) - cursor;
                int stop = Math.min(to, end) - cursor;
                out.add(new Segment(segment.text().substring(start, stop), segment.attrs()));
            }
            cursor = end;
            if (cursor >= to) {
                break;
            }
        }
        return new RichText(Collections.unmodifiableList(out));
    }

    public RichText concat(RichText other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Builder builder = new Builder();
        builder.append(this);
        builder.append(other);
        return builder.build();
    }

    /** 모든 글자의 서식에 change 를 덮어쓴다. 값이 null 인 키는 지운다. */
    public RichText withAttrs(Map<String, Object> change) {
        if (change.isEmpty() || isEmpty()) {
            return this;
        }
        Builder builder = new Builder();
        for (Segment segment : segments) {
            builder.append(segment.text(), Attrs.merge(segment.attrs(), change, false));
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RichText other && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            sb.append('"').append(segment.text()).append('"');
            if (!segment.attrs().isEmpty()) {
                sb.append(segment.attrs());
            }
        }
        return sb.toString();
    }

    /** 이웃한 구간의 서식이 같으면 하나로 합치면서 글을 만든다. */
    public static final class Builder {
        private final List<Segment> out = new ArrayList<>();

        public Builder append(String text, Map<String, Object> attrs) {
            if (text == null || text.isEmpty()) {
                return this;
            }
            Segment next = new Segment(text, attrs);
            if (!out.isEmpty()) {
                Segment last = out.get(out.size() - 1);
                if (last.attrs().equals(next.attrs())) {
                    out.set(out.size() - 1, new Segment(last.text() + next.text(), last.attrs()));
                    return this;
                }
            }
            out.add(next);
            return this;
        }

        public Builder append(RichText text) {
            for (Segment segment : text.segments) {
                append(segment.text(), segment.attrs());
            }
            return this;
        }

        public RichText build() {
            return out.isEmpty() ? EMPTY : new RichText(List.copyOf(out));
        }
    }
}
