package com.copage.identity.domain;

import java.util.List;
import java.util.UUID;

/** 남의 커서 색 12가지 (A-3). 사용자가 고르지 않으면 ID 를 해시해 고른다. 같은 사람은 어느 문서에서나 같은 색이다. */
public final class CursorColors {

    public static final List<String> PALETTE = List.of(
            "#e5484d", "#f76b15", "#ffc53d", "#46a758", "#12a594", "#0090ff",
            "#3e63dd", "#6e56cf", "#ab4aba", "#d6409f", "#8d8d86", "#a18072");

    private CursorColors() {
    }

    public static String byId(UUID userId) {
        return PALETTE.get(Math.floorMod(userId.hashCode(), PALETTE.size()));
    }

    /** 고른 색이 있으면 그것, 없으면 ID 로 정한 색. */
    public static String of(User user) {
        return user.color() != null ? user.color() : byId(user.id());
    }
}
