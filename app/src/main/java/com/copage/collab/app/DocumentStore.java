package com.copage.collab.app;

import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 문서의 연산 로그를 읽고 쓰는 포트. 지금은 메모리 구현만 있고, PostgreSQL 구현(doc_ops, doc_snapshots)이 뒤에 붙는다.
 *
 * <p>구현은 두 가지를 반드시 지킨다. 한 문서의 한 리비전은 하나뿐이고(doc_ops 의 기본 키), 같은 (clientId, clientSeq) 는
 * 한 번만 들어간다(유일 제약). 어기는 append 는 아무것도 쓰지 않고 {@link RevisionConflictException} 을 던진다.
 */
public interface DocumentStore {

    /** 문서의 최신 상태. 한 번도 고친 적 없는 문서는 루트 블록만 있는 빈 문서와 리비전 0 이다. */
    Snapshot load(UUID docId);

    /** fromRev 다음부터 최신까지의 확정 묶음. */
    List<StoredChange> changesSince(UUID docId, long fromRev);

    /** 이 클라이언트의 이 순번이 이미 확정되었으면 그 리비전. */
    OptionalLong committedRev(UUID docId, String clientId, long clientSeq);

    /** 확정 묶음 여러 개를 한 번에 쓴다. 전부 쓰이거나 하나도 쓰이지 않는다. */
    void append(UUID docId, List<StoredChange> changes);

    record Snapshot(Document document, long rev) {
    }

    /** 이미 있는 리비전이나 이미 확정된 순번을 다시 쓰려 했다. 다른 노드가 같은 문서를 쓰고 있다는 뜻일 수 있다. */
    class RevisionConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RevisionConflictException(String message) {
            super(message);
        }
    }
}
