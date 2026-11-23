package com.copage.permission.domain;

/** 페이지 권한 등급 (docs/07-SECURITY.md 9.4). 뒤로 갈수록 높다. */
public enum PageLevel {
    VIEW,
    COMMENT,
    EDIT,
    FULL;

    public boolean atLeast(PageLevel other) {
        return compareTo(other) >= 0;
    }

    /** 둘 중 높은 등급. null 은 없음이다. */
    public static PageLevel max(PageLevel a, PageLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.atLeast(b) ? a : b;
    }
}
