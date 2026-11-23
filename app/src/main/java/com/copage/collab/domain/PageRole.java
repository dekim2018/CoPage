package com.copage.collab.domain;

/** 페이지 권한 등급 (docs/07-SECURITY.md 9.4). 뒤로 갈수록 높다. */
public enum PageRole {
    VIEW,
    COMMENT,
    EDIT,
    FULL;

    public boolean atLeast(PageRole other) {
        return compareTo(other) >= 0;
    }

    /** 둘 중 낮은 등급. */
    public PageRole cap(PageRole limit) {
        return atLeast(limit) ? limit : this;
    }
}
