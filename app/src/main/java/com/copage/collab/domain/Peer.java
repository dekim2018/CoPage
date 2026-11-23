package com.copage.collab.domain;

import java.util.List;
import java.util.UUID;

/**
 * 같은 문서를 연 다른 연결 하나 (docs/05-PROTOCOL.md 7.6). 이름과 색으로 접속자 목록과 남의 커서를 그린다.
 *
 * @param cursor 마지막으로 받은 커서. 아직 받지 않았으면 null
 */
public record Peer(String clientId, UUID userId, String name, String color, Cursor cursor) {

    /** 남의 커서 색 12가지. 같은 사람은 어느 문서에서나 같은 색이다. */
    public static final List<String> COLORS = List.of(
            "#e5484d", "#f76b15", "#ffc53d", "#46a758", "#12a594", "#0090ff",
            "#3e63dd", "#6e56cf", "#ab4aba", "#d6409f", "#8d8d86", "#a18072");

    /** 사용자 ID 를 해시해 색을 고른다. */
    public static String colorOf(UUID userId) {
        return COLORS.get(Math.floorMod(userId.hashCode(), COLORS.size()));
    }
}
