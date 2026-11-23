package com.copage.ot;

/**
 * 형제 블록 사이 순서를 정하는 키를 만든다.
 *
 * <p>두 키 사이에는 항상 새 키를 만들 수 있다. 그래서 블록을 끼워 넣거나 옮길 때 다른 블록의 키를 고치지 않는다. 키는
 * 문자열 순서(String.compareTo)로 비교한다.
 */
public final class FractionalIndex {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = DIGITS.charAt(0);

    private FractionalIndex() {
    }

    /**
     * before 와 after 사이에 놓이는 키를 만든다.
     *
     * @param before 앞 형제의 키. 맨 앞에 넣을 때는 null
     * @param after 뒤 형제의 키. 맨 뒤에 넣을 때는 null
     */
    public static String between(String before, String after) {
        String low = before == null ? "" : before;
        if (after != null && low.compareTo(after) >= 0) {
            throw new IllegalArgumentException("앞 키가 뒤 키보다 작아야 한다: " + before + ", " + after);
        }
        validate(low);
        if (after != null) {
            validate(after);
        }
        return midpoint(low, after);
    }

    private static void validate(String key) {
        for (int i = 0; i < key.length(); i++) {
            if (DIGITS.indexOf(key.charAt(i)) < 0) {
                throw new IllegalArgumentException("순서 키에 쓸 수 없는 글자가 있다: " + key);
            }
        }
        if (!key.isEmpty() && key.charAt(key.length() - 1) == ZERO) {
            throw new IllegalArgumentException("순서 키는 0 으로 끝날 수 없다: " + key);
        }
    }

    private static String midpoint(String low, String high) {
        if (high != null) {
            int common = 0;
            while (common < high.length() && digitAt(low, common) == high.charAt(common)) {
                common++;
            }
            if (common > 0) {
                String lowRest = low.length() > common ? low.substring(common) : "";
                return high.substring(0, common) + midpoint(lowRest, high.substring(common));
            }
        }
        int lowDigit = low.isEmpty() ? 0 : DIGITS.indexOf(low.charAt(0));
        int highDigit = high == null ? DIGITS.length() : DIGITS.indexOf(high.charAt(0));
        if (highDigit - lowDigit > 1) {
            return String.valueOf(DIGITS.charAt((lowDigit + highDigit) / 2));
        }
        if (high != null && high.length() > 1) {
            return high.substring(0, 1);
        }
        String lowRest = low.length() > 1 ? low.substring(1) : "";
        return DIGITS.charAt(lowDigit) + midpoint(lowRest, null);
    }

    private static char digitAt(String key, int index) {
        return index < key.length() ? key.charAt(index) : ZERO;
    }
}
