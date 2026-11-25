package com.copage.collab.app;

import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 문서의 연산 로그와 스냅샷을 읽고 쓰는 포트 (docs/06-DATA-MODEL.md 8.1~8.3). 구현은 PostgreSQL(doc_ops, doc_snapshots)이고,
 * 테스트에는 메모리 구현이 있다.
 *
 * <p>구현은 두 가지를 반드시 지킨다. 한 문서의 한 리비전은 하나뿐이고(doc_ops 의 기본 키), 같은 (clientId, clientSeq) 는
 * 한 번만 들어간다(유일 제약). 어기는 append 는 아무것도 쓰지 않고 {@link RevisionConflictException} 을 던진다.
 */
public interface DocumentStore {

    /**
     * 문서의 최신 상태. 가장 최근 스냅샷에 그 뒤의 연산을 차례로 적용한다. 한 번도 고친 적 없는 문서는 루트 블록만 있는 빈
     * 문서와 리비전 0 이다. 루트 블록 ID 는 문서 ID 다.
     */
    Snapshot load(UUID docId);

    /**
     * rev 시점의 문서 (버전 기록, D-44). rev 이하의 가장 가까운 스냅샷에 그 뒤 연산을 rev 까지 적용한다. 스냅샷은 몇 개만 남으므로
     * 오래된 시점은 빈 문서에서 로그 전체를 다시 적용한다. rev 가 마지막 리비전보다 크면 마지막 리비전까지다.
     */
    Document loadAt(UUID docId, long rev);

    /** 연산 로그를 어디까지 지웠는지 (D-52). 지운 적이 없으면 0. */
    default long compactedThrough(UUID docId) {
        return 0;
    }

    /** fromRev 다음부터 최신까지의 확정 묶음. */
    List<StoredChange> changesSince(UUID docId, long fromRev);

    /**
     * 이 클라이언트의 순번 기록. 연결마다 첫 묶음에서 한 번만 읽는다(PERF-2). 그 뒤의 묶음은 순번이 늘기만 해야 하므로 저장소를
     * 다시 보지 않아도 겹치지 않는다.
     */
    ClientSeqs clientSeqs(UUID docId, String clientId, long clientSeq);

    /**
     * @param committedRev 이 순번이 이미 확정되었으면 그 리비전
     * @param highestSeq 이 클라이언트가 이 문서에 확정한 가장 큰 순번. 없으면 0
     */
    record ClientSeqs(OptionalLong committedRev, long highestSeq) {
    }

    /** 확정 묶음 여러 개를 한 번에 쓴다. 전부 쓰이거나 하나도 쓰이지 않는다. */
    void append(UUID docId, List<StoredChange> changes);

    /**
     * rev 시점의 문서를 스냅샷으로 남긴다. 같은 리비전의 스냅샷이 이미 있으면 아무것도 하지 않는다. 오래된 스냅샷은 구현이 정한
     * 개수만 남기고 지운다.
     */
    void saveSnapshot(UUID docId, Document document, long rev);

    /**
     * @param rev 문서의 최신 리비전
     * @param snapshotRev 읽어 온 스냅샷의 리비전. 스냅샷이 없었으면 0. 세션은 이 뒤로 연산이 얼마나 쌓였는지 보고 다음
     *     스냅샷을 정한다
     */
    record Snapshot(Document document, long rev, long snapshotRev) {
    }

    /** 이미 있는 리비전이나 이미 확정된 순번을 다시 쓰려 했다. 다른 노드가 같은 문서를 쓰고 있다는 뜻일 수 있다. */
    class RevisionConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RevisionConflictException(String message) {
            super(message);
        }
    }
}
