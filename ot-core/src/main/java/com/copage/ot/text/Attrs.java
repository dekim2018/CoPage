package com.copage.ot.text;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 글자 서식(굵게, 링크, 댓글 표시 등)을 다루는 도우미.
 *
 * <p>서식은 이름과 값의 맵이다. 연산의 retain 조각에서는 값이 null 이면 "이 서식을 지운다"는 뜻이고,
 * 문서에 저장된 글자의 서식에는 null 값이 들어가지 않는다.
 */
public final class Attrs {

    public static final Map<String, Object> NONE = Collections.emptyMap();

    private Attrs() {
    }

    /** 키 순서를 고정한 읽기 전용 사본을 만든다. null 값을 허용한다. */
    public static Map<String, Object> copy(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return NONE;
        }
        return Collections.unmodifiableMap(new TreeMap<>(attrs));
    }

    public static Map<String, Object> of(String key, Object value) {
        Map<String, Object> map = new TreeMap<>();
        map.put(key, value);
        return Collections.unmodifiableMap(map);
    }

    /** base 위에 change 를 덮어쓴다. keepNull 이 false 면 null 값(삭제 표시)을 결과에서 뺀다. */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> change, boolean keepNull) {
        if (change.isEmpty() && (keepNull || !base.containsValue(null))) {
            return base;
        }
        Map<String, Object> merged = new TreeMap<>(base);
        merged.putAll(change);
        if (!keepNull) {
            merged.values().removeIf(Objects::isNull);
        }
        return merged.isEmpty() ? NONE : Collections.unmodifiableMap(merged);
    }

    /** attrs 에서 other 가 건드리는 키를 뺀다. 먼저 확정된 쪽이 나중 쪽에 양보할 때 쓴다. */
    public static Map<String, Object> without(Map<String, Object> attrs, Map<String, Object> other) {
        if (attrs.isEmpty() || other.isEmpty()) {
            return attrs;
        }
        Map<String, Object> rest = new LinkedHashMap<>(attrs);
        rest.keySet().removeAll(other.keySet());
        return copy(rest);
    }

    /** change 를 적용하기 전의 값을 current 에서 뽑는다. 없던 서식은 null(지우기)로 적는다. */
    public static Map<String, Object> previousValues(Map<String, Object> current, Map<String, Object> change) {
        Map<String, Object> previous = new TreeMap<>();
        for (String key : change.keySet()) {
            previous.put(key, current.get(key));
        }
        return Collections.unmodifiableMap(previous);
    }
}
