package com.copage.collab.domain;

import com.copage.ot.model.Document;
import java.util.List;

/** 서버가 보내는 메시지 (docs/05-PROTOCOL.md 7.3). 권한 변경과 댓글 알림은 아직 없다. */
public sealed interface ServerMessage
        permits ServerMessage.Welcome, ServerMessage.Ack, ServerMessage.Nack, ServerMessage.Remote, ServerMessage.Presence {

    /**
     * hello 의 답. snapshot 과 ops 중 하나만 있다.
     *
     * @param snapshot 전체 문서. lastRev 가 0 이거나 너무 오래되었을 때 보낸다
     * @param ops lastRev 이후에 확정된 묶음
     * @param rebase false 면 클라이언트는 대기 편집을 합치지 않고 따로 보관한다 (7.5)
     * @param peers 이미 같은 문서를 연 다른 연결과 마지막 커서 (7.6)
     */
    record Welcome(long rev, PageRole role, boolean rebase, Document snapshot, List<StoredChange> ops, List<Peer> peers)
            implements ServerMessage {
    }

    /** 내 묶음이 이 리비전으로 확정되었다. 커밋 뒤에만 보낸다. */
    record Ack(long clientSeq, long rev) implements ServerMessage {
    }

    /** 내 묶음이 거부되었다. rev 는 지금 서버의 리비전이다. */
    record Nack(long clientSeq, String reason, long rev) implements ServerMessage {
    }

    /** 다른 연결의 확정된 묶음. 변환이 끝난 형태다. */
    record Remote(StoredChange change) implements ServerMessage {
    }

    /** 다른 연결이 들어왔거나 커서를 옮겼다(left = false), 또는 나갔다(left = true). */
    record Presence(Peer peer, boolean left) implements ServerMessage {
    }
}
