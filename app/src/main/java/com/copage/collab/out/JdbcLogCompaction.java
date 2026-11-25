package com.copage.collab.out;

import com.copage.collab.app.LogCompaction;
import com.copage.collab.app.VersionLog;
import com.copage.collab.domain.OpJson;
import com.copage.ot.model.Document;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 연산 로그 정리의 PostgreSQL 구현 (D-52). */
public final class JdbcLogCompaction implements LogCompaction {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;

    public JdbcLogCompaction(JdbcClient jdbc, TransactionTemplate tx, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    @Override
    public List<UUID> docsWithOpsBefore(Instant cutoff, int limit) {
        return jdbc.sql("SELECT DISTINCT doc_id FROM doc_ops WHERE created_at < :cutoff LIMIT :limit")
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    @Override
    public OptionalLong lastRevBefore(UUID docId, Instant cutoff) {
        Long rev = jdbc.sql("SELECT max(rev) FROM doc_ops WHERE doc_id = :docId AND created_at < :cutoff")
                .param("docId", docId)
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .single();
        return rev == null ? OptionalLong.empty() : OptionalLong.of(rev);
    }

    @Override
    public List<Version> versionsThrough(UUID docId, long throughRev) {
        return jdbc.sql("SELECT id, rev, kind, created_at FROM doc_versions WHERE doc_id = :docId AND rev <= :rev")
                .param("docId", docId)
                .param("rev", throughRev)
                .query((rs, row) -> new Version(rs.getObject("id", UUID.class), rs.getLong("rev"),
                        VersionLog.Kind.valueOf(rs.getString("kind")), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public Set<Long> snapshotRevs(UUID docId) {
        return new HashSet<>(jdbc.sql("SELECT rev FROM doc_snapshots WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .list());
    }

    @Override
    public void compact(UUID docId, long throughRev, Map<Long, Document> pins, Set<Long> pinExisting,
            Set<UUID> dropVersions, Instant now) {
        tx.executeWithoutResult(status -> {
            for (Map.Entry<Long, Document> pin : pins.entrySet()) {
                jdbc.sql("""
                                INSERT INTO doc_snapshots (doc_id, rev, content, block_count, pinned)
                                VALUES (:docId, :rev, CAST(:content AS jsonb), :blockCount, true)
                                ON CONFLICT (doc_id, rev) DO UPDATE SET pinned = true
                                """)
                        .param("docId", docId)
                        .param("rev", pin.getKey())
                        .param("content", mapper.writeValueAsString(OpJson.document(pin.getValue())))
                        .param("blockCount", pin.getValue().blockCount())
                        .update();
            }
            if (!pinExisting.isEmpty()) {
                jdbc.sql("UPDATE doc_snapshots SET pinned = true WHERE doc_id = :docId AND rev IN (:revs)")
                        .param("docId", docId)
                        .param("revs", pinExisting)
                        .update();
            }
            if (!dropVersions.isEmpty()) {
                jdbc.sql("DELETE FROM doc_versions WHERE doc_id = :docId AND id IN (:ids)")
                        .param("docId", docId)
                        .param("ids", dropVersions)
                        .update();
            }
            jdbc.sql("DELETE FROM doc_ops WHERE doc_id = :docId AND rev <= :rev")
                    .param("docId", docId)
                    .param("rev", throughRev)
                    .update();
            // 지운 지점 앞의 스냅샷은 남은 버전이 가리키는 것만 쓴다.
            jdbc.sql("""
                            DELETE FROM doc_snapshots
                            WHERE doc_id = :docId AND rev < :rev
                              AND rev NOT IN (SELECT rev FROM doc_versions WHERE doc_id = :docId)
                            """)
                    .param("docId", docId)
                    .param("rev", throughRev)
                    .update();
            jdbc.sql("""
                            INSERT INTO doc_compactions (doc_id, through_rev, compacted_at) VALUES (:docId, :rev, :now)
                            ON CONFLICT (doc_id) DO UPDATE SET through_rev = greatest(doc_compactions.through_rev,
                                EXCLUDED.through_rev), compacted_at = EXCLUDED.compacted_at
                            """)
                    .param("docId", docId)
                    .param("rev", throughRev)
                    .param("now", Timestamp.from(now))
                    .update();
        });
    }
}
