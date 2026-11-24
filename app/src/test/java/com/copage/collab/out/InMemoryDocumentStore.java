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
 * 테스트용 메모리 저장소. 문서 세션의 규칙을 DB 없이 확인할 때 쓴다.
 *
 * <p>최신 문서를 함께 들고 있어서 load 가 스냅샷을 읽지 않는다. 저장한 스냅샷은 리비전만 기록해 테스트가 확인한다.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<UUID, Log> logs = new ConcurrentHashMap<>();

    private static final class Log {
        Document document;
        final List<StoredChange> changes = new ArrayList<>();
        final Map<String, Long> revBySeq = new HashMap<>();
        final List<Long> snapshotRevs = new ArrayList<>();

        Log(Document document) {
            this.document = document;
        }
    }

    @Override
    public Snapshot load(UUID docId) {
        Log log = log(docId);
        synchronized (log) {
            long snapshotRev = log.snapshotRevs.isEmpty() ? 0 : log.snapshotRevs.get(log.snapshotRevs.size() - 1);
            return new Snapshot(log.document, log.changes.size(), snapshotRev);
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
    public Document loadAt(UUID docId, long rev) {
        Log log = log(docId);
        synchronized (log) {
            Document document = Document.create(docId.toString());
            for (StoredChange change : log.changes) {
                if (change.rev() <= rev) {
                    document = document.apply(change.ops());
                }
            }
            return document;
        }
    }

    @Override
    public ClientSeqs clientSeqs(UUID docId, String clientId, long clientSeq) {
        Log log = log(docId);
        synchronized (log) {
            Long rev = log.revBySeq.get(key(clientId, clientSeq));
            long highest = log.changes.stream()
                    .filter(change -> change.clientId().equals(clientId))
                    .mapToLong(StoredChange::clientSeq)
                    .max()
                    .orElse(0);
            return new ClientSeqs(rev == null ? OptionalLong.empty() : OptionalLong.of(rev), highest);
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

    @Override
    public void saveSnapshot(UUID docId, Document document, long rev) {
        Log log = log(docId);
        synchronized (log) {
            if (!log.snapshotRevs.contains(rev)) {
                log.snapshotRevs.add(rev);
            }
        }
    }

    /** 지금까지 저장한 스냅샷의 리비전. */
    public List<Long> snapshotRevs(UUID docId) {
        Log log = log(docId);
        synchronized (log) {
            return List.copyOf(log.snapshotRevs);
        }
    }

    private Log log(UUID docId) {
        return logs.computeIfAbsent(docId, id -> new Log(Document.create(id.toString())));
    }

    private static String key(String clientId, long clientSeq) {
        return clientId + "#" + clientSeq;
    }
}
