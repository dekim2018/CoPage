package com.copage.collab.out;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.domain.OpJson;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
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
    public OptionalLong committedRev(UUID docId, String clientId, long clientSeq) {
        return jdbc.sql("""
                        SELECT rev FROM doc_ops
                        WHERE doc_id = :docId AND client_id = :clientId AND client_seq = :clientSeq
                        """)
                .param("docId", docId)
                .param("clientId", clientId)
                .param("clientSeq", clientSeq)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    /** 한 트랜잭션에 여러 행으로 넣는다 (8.2 의 묶음 커밋). 제약 위반이면 하나도 남지 않는다. */
    @Override
    public void append(UUID docId, List<StoredChange> changes) {
        try {
            tx.executeWithoutResult(status -> {
                for (StoredChange change : changes) {
                    jdbc.sql("""
                                    INSERT INTO doc_ops (doc_id, rev, user_id, client_id, client_seq, ops, created_at)
                                    VALUES (:docId, :rev, :userId, :clientId, :clientSeq, CAST(:ops AS jsonb), :createdAt)
                                    """)
                            .param("docId", docId)
                            .param("rev", change.rev())
                            .param("userId", change.userId())
                            .param("clientId", change.clientId())
                            .param("clientSeq", change.clientSeq())
                            .param("ops", mapper.writeValueAsString(OpJson.ops(change.ops())))
                            .param("createdAt", Timestamp.from(change.committedAt()))
                            .update();
                }
            });
        } catch (DuplicateKeyException e) {
            // (doc_id, rev) 가 겹치면 다른 노드가 이 문서를 쓰고 있다는 뜻이다 (5.3).
            throw new RevisionConflictException("문서 " + docId + " 의 리비전이나 순번이 이미 있다: " + e.getMessage());
        }
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
