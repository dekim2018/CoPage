package com.copage.collab.domain;

import com.copage.identity.domain.CursorColors;
import java.util.List;
import java.util.UUID;

/**
 * 같은 문서를 연 다른 연결 하나 (docs/05-PROTOCOL.md 7.6). 이름과 색으로 접속자 목록과 남의 커서를 그린다.
 *
 * @param cursor 마지막으로 받은 커서. 아직 받지 않았으면 null
 */
public record Peer(String clientId, UUID userId, String name, String color, Cursor cursor) {

    /** 남의 커서 색 12가지. 프로필에서 고를 수 있다 (A-3). */
    public static final List<String> COLORS = CursorColors.PALETTE;

    /** 고른 색이 없을 때 사용자 ID 를 해시해 고른다. */
    public static String colorOf(UUID userId) {
        return CursorColors.byId(userId);
    }
}
