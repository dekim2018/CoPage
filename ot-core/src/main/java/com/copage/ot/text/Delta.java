package com.copage.ot.text;

import com.copage.ot.text.Component.Delete;
import com.copage.ot.text.Component.Insert;
import com.copage.ot.text.Component.Retain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 한 블록의 글에 대한 편집. 조각(retain, insert, delete)의 나열이다.
 *
 * <p>항상 정규화된 상태로 만든다. 같은 종류의 이웃 조각은 합치고, 같은 자리의 삽입과 삭제는 삽입을 먼저 두고,
 * 끝에 남는 서식 없는 retain 은 적지 않는다. 그래서 같은 편집은 같은 값이 되고 equals 로 비교할 수 있다.
 */
public final class Delta {

    public static final Delta NOOP = new Delta(List.of());

    private final List<Component> components;

    private Delta(List<Component> normalized) {
        this.components = normalized;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Delta of(List<Component> components) {
        Builder builder = new Builder();
        components.forEach(builder::push);
        return builder.build();
    }

    public List<Component> components() {
        return components;
    }

    public boolean isNoop() {
        return components.isEmpty();
    }

    /** 이 편집을 적용하려면 글이 최소 몇 글자여야 하는지. */
    public int baseLength() {
        int total = 0;
        for (Component component : components) {
            total += component.baseLength();
        }
        return total;
    }

    /** 적용 후 글 길이가 얼마나 바뀌는지 (넣은 글자 수 - 지운 글자 수). */
    public int lengthChange() {
        int change = 0;
        for (Component component : components) {
            if (component instanceof Insert insert) {
                change += insert.length();
            } else if (component instanceof Delete delete) {
                change -= delete.count();
            }
        }
        return change;
    }

    /** 글에 적용한 결과를 돌려준다. 길이가 맞지 않거나 이모지의 가운데를 자르면 예외를 던진다. */
    public RichText apply(RichText text) {
        if (baseLength() > text.length()) {
            throw new IllegalArgumentException(
                    "편집이 글 길이를 넘는다: 필요 " + baseLength() + ", 실제 " + text.length());
        }
        RichText.Builder out = new RichText.Builder();
        int cursor = 0;
        for (Component component : components) {
            if (text.splitsSurrogatePair(cursor)) {
                throw new IllegalArgumentException("코드 단위 2개짜리 글자의 가운데(위치 " + cursor + ")를 자를 수 없다");
            }
            switch (component) {
                case Retain retain -> {
                    out.append(text.slice(cursor, cursor + retain.count()).withAttrs(retain.attrs()));
                    cursor += retain.count();
                }
                case Insert insert -> out.append(insert.text(), insert.attrs());
                case Delete delete -> cursor += delete.count();
            }
        }
        if (text.splitsSurrogatePair(cursor)) {
            throw new IllegalArgumentException("코드 단위 2개짜리 글자의 가운데(위치 " + cursor + ")를 자를 수 없다");
        }
        out.append(text.slice(cursor, text.length()));
        return out.build();
    }

    /**
     * 같은 글에서 출발한 두 편집 중, 이 편집을 other 가 먼저 적용된 뒤의 글에 맞게 바꾼다.
     *
     * @param thisIsLater 이 편집이 other 보다 나중에 확정되면 true. 같은 자리에 둘 다 삽입하면 먼저 확정된 쪽의 글자가
     *     앞에 오고, 같은 글자의 같은 서식을 둘 다 바꾸면 나중에 확정된 쪽의 값이 남는다.
     */
    public Delta transform(Delta other, boolean thisIsLater) {
        Cursor mine = new Cursor(components);
        Cursor theirs = new Cursor(other.components);
        Builder out = new Builder();
        while (mine.hasNext() || theirs.hasNext()) {
            if (theirs.peek() instanceof Insert && (thisIsLater || !(mine.peek() instanceof Insert))) {
                out.retain(theirs.next().length());
            } else if (mine.peek() instanceof Insert) {
                out.push(mine.next());
            } else {
                int length = Math.min(mine.peekLength(), theirs.peekLength());
                Component a = mine.next(length);
                Component b = theirs.next(length);
                if (b instanceof Delete) {
                    continue;
                }
                if (a instanceof Delete) {
                    out.delete(length);
                } else {
                    Map<String, Object> attrs = ((Retain) a).attrs();
                    Map<String, Object> theirAttrs = ((Retain) b).attrs();
                    out.retain(length, thisIsLater ? attrs : Attrs.without(attrs, theirAttrs));
                }
            }
        }
        return out.build();
    }

    /** 이 편집을 적용한 뒤 next 를 적용한 것과 같은 편집 하나를 만든다. */
    public Delta compose(Delta next) {
        Cursor first = new Cursor(components);
        Cursor second = new Cursor(next.components);
        Builder out = new Builder();
        while (first.hasNext() || second.hasNext()) {
            if (second.peek() instanceof Insert) {
                out.push(second.next());
            } else if (first.peek() instanceof Delete) {
                out.push(first.next());
            } else {
                int length = Math.min(first.peekLength(), second.peekLength());
                Component a = first.next(length);
                Component b = second.next(length);
                if (b instanceof Retain retainB) {
                    if (a instanceof Retain retainA) {
                        out.retain(length, Attrs.merge(retainA.attrs(), retainB.attrs(), true));
                    } else {
                        Insert insertA = (Insert) a;
                        out.insert(insertA.text(), Attrs.merge(insertA.attrs(), retainB.attrs(), false));
                    }
                } else if (a instanceof Retain) {
                    out.delete(length);
                }
            }
        }
        return out.build();
    }

    /** 적용 전의 글 base 를 보고, 이 편집을 되돌리는 편집을 만든다. */
    public Delta invert(RichText base) {
        Builder out = new Builder();
        int cursor = 0;
        for (Component component : components) {
            switch (component) {
                case Retain retain -> {
                    if (retain.attrs().isEmpty()) {
                        out.retain(retain.count());
                    } else {
                        for (Segment segment : base.slice(cursor, cursor + retain.count()).segments()) {
                            out.retain(segment.length(), Attrs.previousValues(segment.attrs(), retain.attrs()));
                        }
                    }
                    cursor += retain.count();
                }
                case Insert insert -> out.delete(insert.length());
                case Delete delete -> {
                    for (Segment segment : base.slice(cursor, cursor + delete.count()).segments()) {
                        out.insert(segment.text(), segment.attrs());
                    }
                    cursor += delete.count();
                }
            }
        }
        return out.build();
    }

    /**
     * 글이 offset 에서 둘로 나뉠 때 이 편집을 앞부분용과 뒷부분용으로 가른다.
     *
     * <p>정확히 offset 자리에 넣는 글자는 앞부분의 끝에 남긴다. {@link #transformPosition(int)} 과 같은 규칙이어야
     * 나누기와 텍스트 편집을 어느 순서로 적용해도 결과가 같다.
     */
    public Delta[] splitAt(int offset) {
        Builder head = new Builder();
        Builder tail = new Builder();
        int cursor = 0;
        for (Component component : components) {
            if (component instanceof Insert) {
                (cursor <= offset ? head : tail).push(component);
                continue;
            }
            int length = component.baseLength();
            int inHead = Math.max(0, Math.min(cursor + length, offset) - cursor);
            if (inHead > 0) {
                head.push(resize(component, inHead));
            }
            if (length - inHead > 0) {
                tail.push(resize(component, length - inHead));
            }
            cursor += length;
        }
        return new Delta[] {head.build(), tail.build()};
    }

    /** 이 편집이 적용된 뒤 원래 위치 offset 이 어디로 옮겨 가는지. offset 자리의 삽입은 offset 앞에 들어간 것으로 본다. */
    public int transformPosition(int offset) {
        int cursor = 0;
        int moved = offset;
        for (Component component : components) {
            if (cursor > offset) {
                break;
            }
            switch (component) {
                case Insert insert -> moved += insert.length();
                case Delete delete -> {
                    moved -= Math.max(0, Math.min(cursor + delete.count(), offset) - cursor);
                    cursor += delete.count();
                }
                case Retain retain -> cursor += retain.count();
            }
        }
        return moved;
    }

    /** 앞에 글자 count 개가 더 붙은 글에 적용할 수 있게 옮긴다. 블록이 다른 블록 끝에 합쳐졌을 때 쓴다. */
    public Delta shifted(int count) {
        if (count == 0 || isNoop()) {
            return this;
        }
        Builder out = new Builder();
        out.retain(count);
        components.forEach(out::push);
        return out.build();
    }

    private static Component resize(Component component, int length) {
        return switch (component) {
            case Retain retain -> new Retain(length, retain.attrs());
            case Delete delete -> new Delete(length);
            case Insert insert -> throw new IllegalStateException("삽입은 길이를 바꾸지 않는다");
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Delta other && components.equals(other.components);
    }

    @Override
    public int hashCode() {
        return components.hashCode();
    }

    @Override
    public String toString() {
        return components.toString();
    }

    /** 조각을 차례로 받아 정규화된 편집을 만든다. */
    public static final class Builder {
        private final List<Component> out = new ArrayList<>();

        public Builder retain(int count) {
            return retain(count, Attrs.NONE);
        }

        public Builder retain(int count, Map<String, Object> attrs) {
            if (count > 0) {
                push(new Retain(count, attrs));
            }
            return this;
        }

        public Builder insert(String text) {
            return insert(text, Attrs.NONE);
        }

        public Builder insert(String text, Map<String, Object> attrs) {
            if (text != null && !text.isEmpty()) {
                push(new Insert(text, attrs));
            }
            return this;
        }

        public Builder delete(int count) {
            if (count > 0) {
                push(new Delete(count));
            }
            return this;
        }

        public Builder push(Component next) {
            int lastIndex = out.size() - 1;
            if (lastIndex < 0) {
                out.add(next);
                return this;
            }
            Component last = out.get(lastIndex);
            if (next instanceof Delete delete && last instanceof Delete lastDelete) {
                out.set(lastIndex, new Delete(lastDelete.count() + delete.count()));
            } else if (next instanceof Retain retain
                    && last instanceof Retain lastRetain
                    && lastRetain.attrs().equals(retain.attrs())) {
                out.set(lastIndex, new Retain(lastRetain.count() + retain.count(), retain.attrs()));
            } else if (next instanceof Insert insert) {
                // 같은 자리의 삽입과 삭제는 결과가 같으므로 항상 삽입을 앞에 둔다.
                int at = last instanceof Delete ? lastIndex : lastIndex + 1;
                if (at > 0 && out.get(at - 1) instanceof Insert before && before.attrs().equals(insert.attrs())) {
                    out.set(at - 1, new Insert(before.text() + insert.text(), insert.attrs()));
                } else {
                    out.add(at, insert);
                }
            } else {
                out.add(next);
            }
            return this;
        }

        public Delta build() {
            int end = out.size();
            while (end > 0 && out.get(end - 1) instanceof Retain retain && retain.attrs().isEmpty()) {
                end--;
            }
            return end == 0 ? NOOP : new Delta(List.copyOf(out.subList(0, end)));
        }
    }

    /** 조각 목록을 원하는 길이만큼 잘라 가며 읽는다. 다 읽은 뒤에는 서식 없는 retain 이 끝없이 이어지는 것으로 본다. */
    private static final class Cursor {
        private final List<Component> components;
        private int index;
        private int offset;

        Cursor(List<Component> components) {
            this.components = components;
        }

        boolean hasNext() {
            return index < components.size();
        }

        Component peek() {
            return hasNext() ? components.get(index) : null;
        }

        int peekLength() {
            return hasNext() ? components.get(index).length() - offset : Integer.MAX_VALUE;
        }

        Component next() {
            return next(Integer.MAX_VALUE);
        }

        Component next(int length) {
            if (!hasNext()) {
                return new Retain(length, Attrs.NONE);
            }
            Component current = components.get(index);
            int remaining = current.length() - offset;
            int take = Math.min(length, remaining);
            int from = offset;
            if (take == remaining) {
                index++;
                offset = 0;
            } else {
                offset += take;
            }
            return switch (current) {
                case Retain retain -> new Retain(take, retain.attrs());
                case Delete delete -> new Delete(take);
                case Insert insert -> new Insert(insert.text().substring(from, from + take), insert.attrs());
            };
        }
    }
}
