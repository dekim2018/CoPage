package com.copage.collab.out;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리에 두는 연산 로그. 서버를 다시 띄우면 사라진다. PostgreSQL 구현이 붙을 때까지 쓴다.
 *
 * <p>문서의 루트 블록 ID 는 문서 ID 다 (docs/06-DATA-MODEL.md 의 pages: id 가 곧 문서 ID). 스냅샷은 따로 두지 않고 최신
 * 문서를 함께 들고 있다.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<UUID, Log> logs = new ConcurrentHashMap<>();

    private static final class Log {
        Document document;
        final List<StoredChange> changes = new ArrayList<>();
        final Map<String, Long> revBySeq = new HashMap<>();

        Log(Document document) {
            this.document = document;
        }
    }

    @Override
    public Snapshot load(UUID docId) {
        Log log = log(docId);
        synchronized (log) {
            return new Snapshot(log.document, log.changes.size());
        }
    }

    @Override
    public List<StoredChange> changesSince(UUID docId, long fromRev) {
        Log log = log(docId);
        synchronized (log) {
            if (fromRev < 0 || fromRev > log.changes.size()) {
                throw new IllegalArgumentException("리비전 " + fromRev + " 이후의 기록이 없다");
            }
            return List.copyOf(log.changes.subList((int) fromRev, log.changes.size()));
        }
    }

    @Override
    public OptionalLong committedRev(UUID docId, String clientId, long clientSeq) {
        Log log = log(docId);
        synchronized (log) {
            Long rev = log.revBySeq.get(key(clientId, clientSeq));
            return rev == null ? OptionalLong.empty() : OptionalLong.of(rev);
        }
    }

    @Override
    public void append(UUID docId, List<StoredChange> changes) {
        Log log = log(docId);
        synchronized (log) {
            // 먼저 전부 검사하고, 문제가 없을 때만 쓴다.
            long expected = log.changes.size() + 1;
            Document next = log.document;
            List<String> keys = new ArrayList<>();
            for (StoredChange change : changes) {
                if (change.rev() != expected) {
                    throw new RevisionConflictException("리비전 " + change.rev() + " 을 쓸 수 없다. 다음은 " + expected);
                }
                String key = key(change.clientId(), change.clientSeq());
                if (log.revBySeq.containsKey(key) || keys.contains(key)) {
                    throw new RevisionConflictException("이미 확정된 순번이다: " + key);
                }
                keys.add(key);
                next = next.apply(change.ops());
                expected++;
            }
            for (StoredChange change : changes) {
                log.changes.add(change);
                log.revBySeq.put(key(change.clientId(), change.clientSeq()), change.rev());
            }
            log.document = next;
        }
    }

    private Log log(UUID docId) {
        return logs.computeIfAbsent(docId, id -> new Log(Document.create(id.toString())));
    }

    private static String key(String clientId, long clientSeq) {
        return clientId + "#" + clientSeq;
    }
}
