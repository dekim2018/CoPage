package com.copage.ot;

/**
 * 형제 블록(그리고 형제 페이지) 사이 순서를 정하는 키를 만든다.
 *
 * <p>두 키 사이에는 항상 새 키를 만들 수 있다. 그래서 블록을 끼워 넣거나 옮길 때 다른 블록의 키를 고치지 않는다. 키는
 * 62진 소수 문자열이고 문자열 순서(String.compareTo)로 비교한다. 0 으로 끝나지 않는다.
 *
 * <p>맨 뒤에 붙이기와 맨 앞에 넣기는 키가 길어지지 않게 따로 다룬다 (진단 DC-5, docs/review/DECISIONS.md D-24). 예전에는 남은
 * 구간의 가운데를 골라 한 번 붙일 때마다 남은 구간이 반으로 줄었고, 그래서 키 길이가 붙인 횟수에 비례해 자랐다(1,000번에 167
 * 글자). 지금은 첫 글자가 같은 키들 안에서 정해진 길이로 하나씩 세어 올라가고, 그 길이를 다 쓰면 첫 글자를 하나 올린다. 첫
 * 글자가 'V' 에서 멀어질수록 세는 길이를 늘리되(1, 2, 3, 최대 4) 4 에서 멈춘다. 그래서 길이는 붙인 횟수의 로그로만 자란다.
 * 맨 앞에 넣기는 그 거울이다. 키의 모양과 비교 방법이 그대로라 예전 키와 섞여도 순서가 맞다.
 */
public final class FractionalIndex {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = DIGITS.charAt(0);
    private static final char LAST = DIGITS.charAt(DIGITS.length() - 1);
    /** 처음 만드는 키. 가운데 글자다. */
    private static final int MIDDLE = DIGITS.length() / 2;
    /** 첫 글자 하나 아래에서 세는 길이의 최대. 62^4 개 남짓을 세고 나서야 첫 글자를 올린다. */
    private static final int MAX_LEVEL = 4;

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
        if (after == null) {
            return low.isEmpty() ? String.valueOf(DIGITS.charAt(MIDDLE)) : append(low);
        }
        if (low.isEmpty()) {
            return prepend(after);
        }
        return midpoint(low, after);
    }

    /**
     * low 바로 뒤의 짧은 키. low 는 비어 있지 않다.
     *
     * <p>첫 글자가 가장 큰 'z' 인 키는 그 글자 아래에서 세지 않는다. 'z' 로 시작하는 키 위에는 'z' 로 시작하는 키만 있어서, 그
     * 자리에서 세면 곧 위가 막혀 다시 한 글자씩 길어진다. 대신 'z' 를 떼고 나머지에서 같은 일을 한다. 예전 방식이 만든 'zzzzV'
     * 같은 키 뒤에서도 그래서 짧게 이어 간다.
     */
    private static String append(String low) {
        int first = DIGITS.indexOf(low.charAt(0));
        String rest = low.substring(1);
        if (first == DIGITS.length() - 1) {
            return LAST + (rest.isEmpty() ? String.valueOf(DIGITS.charAt(MIDDLE)) : append(rest));
        }
        String suffix = increaseTo(rest, appendLevel(first));
        if (suffix != null) {
            return low.charAt(0) + suffix;
        }
        int next = first + 1;
        if (next == DIGITS.length() - 1) {
            return String.valueOf(LAST) + DIGITS.charAt(MIDDLE);
        }
        return DIGITS.charAt(next) + String.valueOf(ZERO).repeat(appendLevel(next) - 1) + DIGITS.charAt(1);
    }

    /** high 바로 앞의 짧은 키. high 는 비어 있지 않다. 맨 뒤에 붙이기의 거울이다. '0' 으로 시작하면 '0' 을 떼고 나머지에서 한다. */
    private static String prepend(String high) {
        int first = DIGITS.indexOf(high.charAt(0));
        String rest = high.substring(1);
        if (first == 0) {
            // 키는 0 으로 끝나지 않으므로 rest 는 비어 있지 않다.
            return ZERO + prepend(rest);
        }
        String suffix = decreaseTo(rest, prependLevel(first));
        if (suffix != null) {
            return high.charAt(0) + suffix;
        }
        int next = first - 1;
        if (next == 0) {
            return String.valueOf(ZERO) + DIGITS.charAt(MIDDLE);
        }
        return DIGITS.charAt(next) + String.valueOf(LAST).repeat(prependLevel(next));
    }

    /** 첫 글자가 first 인 키들을 맨 뒤에 붙일 때 세는 길이. 가운데('V')에서 멀수록 길다. */
    private static int appendLevel(int first) {
        return Math.min(MAX_LEVEL, Math.max(1, first - MIDDLE + 1));
    }

    private static int prependLevel(int first) {
        return Math.min(MAX_LEVEL, Math.max(1, MIDDLE - first));
    }

    /** 길이가 length 이고 rest 보다 큰 가장 작은 문자열. 0 으로 끝나지 않는다. 없으면 null. */
    private static String increaseTo(String rest, int length) {
        String candidate;
        if (rest.length() < length) {
            candidate = rest + String.valueOf(ZERO).repeat(length - rest.length() - 1) + DIGITS.charAt(1);
        } else {
            candidate = increment(rest.substring(0, length));
        }
        if (candidate != null && candidate.charAt(candidate.length() - 1) == ZERO) {
            candidate = increment(candidate);
        }
        return candidate;
    }

    /** 길이가 length 이고 rest 보다 작은 가장 큰 문자열. 0 으로 끝나지 않는다. 없으면 null. */
    private static String decreaseTo(String rest, int length) {
        String candidate;
        if (rest.length() > length) {
            candidate = rest.substring(0, length);
        } else {
            candidate = decrement(rest + String.valueOf(ZERO).repeat(length - rest.length()));
        }
        if (candidate != null && candidate.charAt(candidate.length() - 1) == ZERO) {
            candidate = decrement(candidate);
        }
        return candidate;
    }

    /** 같은 길이에서 1 더한다. 넘치면 null. */
    private static String increment(String digits) {
        char[] chars = digits.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            int digit = DIGITS.indexOf(chars[i]);
            if (digit < DIGITS.length() - 1) {
                chars[i] = DIGITS.charAt(digit + 1);
                return new String(chars);
            }
            chars[i] = ZERO;
        }
        return null;
    }

    /** 같은 길이에서 1 뺀다. 0 아래로 내려가거나 모두 0 이 되면 null. */
    private static String decrement(String digits) {
        char[] chars = digits.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            int digit = DIGITS.indexOf(chars[i]);
            if (digit > 0) {
                chars[i] = DIGITS.charAt(digit - 1);
                String result = new String(chars);
                return result.chars().allMatch(c -> c == ZERO) ? null : result;
            }
            chars[i] = LAST;
        }
        return null;
    }

    /** between 이 받는 키인지. 비어 있지 않고, 쓸 수 있는 글자로만 되어 있고, 0 으로 끝나지 않는다. */
    public static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.charAt(key.length() - 1) == ZERO) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (DIGITS.indexOf(key.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
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
