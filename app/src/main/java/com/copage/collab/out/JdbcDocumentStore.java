package com.copage.collab.out;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.domain.OpJson;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 의 doc_ops, doc_snapshots 에 두는 저장소 (docs/06-DATA-MODEL.md 8.1~8.3).
 *
 * <p>협업 경로라서 JPA 를 거치지 않고 SQL 을 직접 쓴다 (docs/02-TECH-STACK.md 4.1). 연산과 문서는 WebSocket 메시지와 같은
 * JSON 형식({@link OpJson})으로 jsonb 에 넣는다.
 */
public final class JdbcDocumentStore implements DocumentStore {

    /** INSERT 문 하나에 넣는 행 수. 행마다 매개변수가 7개라 한도(65,535)보다 넉넉히 작다. */
    static final int ROWS_PER_INSERT = 1_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;
    private final int snapshotsKept;

    /** @param snapshotsKept 문서마다 남길 스냅샷 수. 새 스냅샷을 저장할 때 오래된 것부터 지운다 */
    public JdbcDocumentStore(JdbcClient jdbc, TransactionTemplate tx, JsonMapper mapper, int snapshotsKept) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.snapshotsKept = snapshotsKept;
    }

    private record StoredSnapshot(long rev, String content) {
    }

    @Override
    public Snapshot load(UUID docId) {
        return tx.execute(status -> {
            Optional<StoredSnapshot> snapshot = jdbc.sql("""
                            SELECT rev, content FROM doc_snapshots
                            WHERE doc_id = :docId ORDER BY rev DESC LIMIT 1
                            """)
                    .param("docId", docId)
                    .query((rs, row) -> new StoredSnapshot(rs.getLong("rev"), rs.getString("content")))
                    .optional();
            Document document = snapshot
                    .map(s -> OpJson.readDocument(mapper.readTree(s.content())))
                    .orElseGet(() -> Document.create(docId.toString()));
            long snapshotRev = snapshot.map(StoredSnapshot::rev).orElse(0L);
            long rev = snapshotRev;
            for (StoredChange change : changesSince(docId, snapshotRev)) {
                if (change.rev() != rev + 1) {
                    throw new IllegalStateException("문서 " + docId + " 의 연산 로그에 리비전 " + (rev + 1) + " 이 없다");
                }
                document = document.apply(change.ops());
                rev = change.rev();
            }
            return new Snapshot(document, rev, snapshotRev);
        });
    }

    @Override
    public List<StoredChange> changesSince(UUID docId, long fromRev) {
        return jdbc.sql("""
                        SELECT rev, user_id, client_id, client_seq, ops, created_at FROM doc_ops
                        WHERE doc_id = :docId AND rev > :fromRev ORDER BY rev
                        """)
                .param("docId", docId)
                .param("fromRev", fromRev)
                .query(this::change)
                .list();
    }

    @Override
    public ClientSeqs clientSeqs(UUID docId, String clientId, long clientSeq) {
        // 왕복 한 번으로 둘 다 읽는다. 둘 다 (doc_id, client_id, client_seq) 유일 색인으로 찾는다.
        return jdbc.sql("""
                        SELECT
                          (SELECT rev FROM doc_ops
                           WHERE doc_id = :docId AND client_id = :clientId AND client_seq = :clientSeq) AS rev,
                          (SELECT coalesce(max(client_seq), 0) FROM doc_ops
                           WHERE doc_id = :docId AND client_id = :clientId) AS highest
                        """)
                .param("docId", docId)
                .param("clientId", clientId)
                .param("clientSeq", clientSeq)
                .query((rs, row) -> {
                    long rev = rs.getLong("rev");
                    return new ClientSeqs(rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(rev), rs.getLong("highest"));
                })
                .single();
    }

    /**
     * 여러 행을 INSERT 문 하나로 넣는다 (8.2 의 묶음 커밋). 문장 하나는 그 자체로 원자적이라 제약 위반이면 하나도 남지 않고,
     * {@link #ROWS_PER_INSERT} 행까지는 DB 왕복이 한 번이다(PERF-2). 행마다 INSERT 를 보내면 묶음 N 개에 왕복 N+1 번(BEGIN 과
     * 첫 INSERT, 나머지 INSERT, COMMIT)이었다.
     *
     * <p>문장 하나의 매개변수는 65,535 개까지라(PostgreSQL 규약의 2바이트 개수) {@link #ROWS_PER_INSERT} 행씩 나눈다.
     * 나뉘면 한 트랜잭션 안에서 넣어 원자성을 지킨다. DB 가 멈췄다 돌아온 직후처럼 묶음이 아주 클 때만 그렇다.
     */
    @Override
    public void append(UUID docId, List<StoredChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            if (changes.size() <= ROWS_PER_INSERT) {
                insert(docId, changes);
            } else {
                tx.executeWithoutResult(status -> {
                    for (int from = 0; from < changes.size(); from += ROWS_PER_INSERT) {
                        insert(docId, changes.subList(from, Math.min(changes.size(), from + ROWS_PER_INSERT)));
                    }
                });
            }
        } catch (DuplicateKeyException e) {
            // (doc_id, rev) 가 겹치면 다른 노드가 이 문서를 쓰고 있다는 뜻이다 (5.3).
            throw new RevisionConflictException("문서 " + docId + " 의 리비전이나 순번이 이미 있다: " + e.getMessage());
        }
    }

    private void insert(UUID docId, List<StoredChange> changes) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO doc_ops (doc_id, rev, user_id, client_id, client_seq, ops, created_at) VALUES ");
        Map<String, Object> params = new HashMap<>();
        params.put("docId", docId);
        for (int i = 0; i < changes.size(); i++) {
            StoredChange change = changes.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:docId, :rev").append(i).append(", :userId").append(i).append(", :clientId").append(i)
                    .append(", :clientSeq").append(i).append(", CAST(:ops").append(i).append(" AS jsonb), :createdAt")
                    .append(i).append(')');
            params.put("rev" + i, change.rev());
            params.put("userId" + i, change.userId());
            params.put("clientId" + i, change.clientId());
            params.put("clientSeq" + i, change.clientSeq());
            params.put("ops" + i, mapper.writeValueAsString(OpJson.ops(change.ops())));
            params.put("createdAt" + i, Timestamp.from(change.committedAt()));
        }
        jdbc.sql(sql.toString()).params(params).update();
    }

    @Override
    public void saveSnapshot(UUID docId, Document document, long rev) {
        String content = mapper.writeValueAsString(OpJson.document(document));
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO doc_snapshots (doc_id, rev, content, block_count)
                            VALUES (:docId, :rev, CAST(:content AS jsonb), :blockCount)
                            ON CONFLICT (doc_id, rev) DO NOTHING
                            """)
                    .param("docId", docId)
                    .param("rev", rev)
                    .param("content", content)
                    .param("blockCount", document.blockCount())
                    .update();
            jdbc.sql("""
                            DELETE FROM doc_snapshots
                            WHERE doc_id = :docId AND rev NOT IN (
                                SELECT rev FROM doc_snapshots WHERE doc_id = :docId ORDER BY rev DESC LIMIT :kept)
                            """)
                    .param("docId", docId)
                    .param("kept", snapshotsKept)
                    .update();
        });
    }

    private StoredChange change(ResultSet rs, int row) throws SQLException {
        return new StoredChange(
                rs.getLong("rev"),
                rs.getObject("user_id", UUID.class),
                rs.getString("client_id"),
                rs.getLong("client_seq"),
                OpJson.readOps(mapper.readTree(rs.getString("ops"))),
                rs.getTimestamp("created_at").toInstant());
    }
}
