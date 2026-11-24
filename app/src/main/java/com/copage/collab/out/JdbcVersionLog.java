package com.copage.collab.out;

import com.copage.collab.app.VersionLog;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** doc_versions (D-44). */
public final class JdbcVersionLog implements VersionLog {

    /** 목록의 한 버전에 보일 편집자 수. */
    private static final int EDITORS_SHOWN = 5;

    private final JdbcClient jdbc;

    public JdbcVersionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Version> list(UUID docId, Long beforeRev, int limit) {
        // 앞 버전(같은 문서의 바로 아래 리비전) 다음부터 이 리비전까지 편집한 사람을 연산 로그에서 모은다.
        return jdbc.sql("""
                        WITH listed AS (
                            SELECT v.*, lag(v.rev, 1, 0::bigint) OVER (ORDER BY v.rev, v.created_at) AS prev_rev
                            FROM doc_versions v
                            WHERE v.doc_id = :docId
                        )
                        SELECT l.id, l.rev, l.kind, l.name, l.created_by, l.created_at,
                               ARRAY(
                                   SELECT u.name FROM users u
                                   WHERE u.id IN (
                                       SELECT DISTINCT o.user_id FROM doc_ops o
                                       WHERE o.doc_id = :docId AND o.rev > l.prev_rev AND o.rev <= l.rev
                                   )
                                   ORDER BY u.name LIMIT :editors
                               ) AS editors
                        FROM listed l
                        WHERE (CAST(:beforeRev AS bigint) IS NULL OR l.rev < :beforeRev)
                        ORDER BY l.rev DESC, l.created_at DESC
                        LIMIT :limit
                        """)
                .param("docId", docId)
                .param("beforeRev", beforeRev, java.sql.Types.BIGINT)
                .param("editors", EDITORS_SHOWN)
                .param("limit", limit)
                .query((rs, row) -> version(rs))
                .list();
    }

    @Override
    public Version record(UUID docId, Kind kind, String name, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO doc_versions (id, doc_id, rev, kind, name, created_by)
                        VALUES (:id, :docId,
                                (SELECT coalesce(max(rev), 0) FROM doc_ops WHERE doc_id = :docId),
                                :kind, :name, :createdBy)
                        ON CONFLICT (doc_id, rev, kind) DO UPDATE SET name = coalesce(EXCLUDED.name, doc_versions.name)
                        RETURNING id, rev, kind, name, created_by, created_at, ARRAY[]::text[] AS editors
                        """)
                .param("id", UUID.randomUUID())
                .param("docId", docId)
                .param("kind", kind.name())
                .param("name", name)
                .param("createdBy", createdBy)
                .query((rs, row) -> version(rs))
                .single();
    }

    @Override
    public int recordAutoVersions(Duration quiet, Duration every) {
        return jdbc.sql("""
                        INSERT INTO doc_versions (id, doc_id, rev, kind)
                        SELECT gen_random_uuid(), o.doc_id, o.max_rev, 'AUTO'
                        FROM (
                            SELECT doc_id, max(rev) AS max_rev, max(created_at) AS last_at
                            FROM doc_ops
                            WHERE created_at > now() - interval '1 day'
                            GROUP BY doc_id
                        ) o
                        LEFT JOIN (
                            SELECT doc_id, max(rev) AS v_rev, max(created_at) AS v_at FROM doc_versions GROUP BY doc_id
                        ) v ON v.doc_id = o.doc_id
                        WHERE o.max_rev > coalesce(v.v_rev, 0)
                          AND (o.last_at <= now() - make_interval(secs => :quiet)
                               OR coalesce(v.v_at, '-infinity'::timestamptz) <= now() - make_interval(secs => :every))
                        ON CONFLICT (doc_id, rev, kind) DO NOTHING
                        """)
                .param("quiet", quiet.toMillis() / 1000.0)
                .param("every", every.toMillis() / 1000.0)
                .update();
    }

    private static Version version(ResultSet rs) throws SQLException {
        Array editors = rs.getArray("editors");
        return new Version(
                rs.getObject("id", UUID.class),
                rs.getLong("rev"),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                editors == null ? List.of() : Arrays.asList((String[]) editors.getArray()));
    }
}
