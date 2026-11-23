package com.copage.collab.domain;

import com.copage.ot.op.Op;
import java.util.List;

/** 클라이언트가 보내는 메시지 (docs/05-PROTOCOL.md 7.2). */
public sealed interface ClientMessage
        permits ClientMessage.Hello, ClientMessage.Change, ClientMessage.Presence, ClientMessage.Ping {

    /**
     * 연결 직후 한 번.
     *
     * @param lastRev 클라이언트가 마지막으로 확인한 리비전. 처음 여는 문서면 0 이다
     * @param resume 확정 문서를 들고 있어서 lastRev 부터 이어 붙으려면 true. 리비전 0 인 새 문서를 열고 편집하다 끊긴
     *     클라이언트도 lastRev 가 0 이라서, lastRev 만으로는 처음 여는 것과 구분할 수 없다
     */
    record Hello(String clientId, long lastRev, boolean resume) implements ClientMessage {
    }

    /** 편집 묶음. clientSeq 는 클라이언트별로 1씩 늘어난다. */
    record Change(long clientSeq, long baseRev, List<Op> ops) implements ClientMessage {
        public Change {
            ops = List.copyOf(ops);
        }
    }

    /** 커서와 선택 영역 (7.6). 편집기를 떠났으면 cursor 는 null 이다. 저장하지 않는다. */
    record Presence(Cursor cursor) implements ClientMessage {
    }

    /** 연결이 살아 있다는 신호. 20초마다 온다. */
    record Ping() implements ClientMessage {
    }
}
