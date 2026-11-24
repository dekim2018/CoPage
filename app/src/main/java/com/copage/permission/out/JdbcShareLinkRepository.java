package com.copage.permission.out;

import com.copage.permission.app.ShareLinkRepository;
import com.copage.permission.domain.PageLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** share_links (docs/06-DATA-MODEL.md 8.5, D-41). */
public final class JdbcShareLinkRepository implements ShareLinkRepository {

    private static final String COLUMNS = "id, page_id, level, expires_at, created_by, created_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcShareLinkRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void replace(ShareLink link, String tokenHash) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM share_links WHERE page_id = :pageId").param("pageId", link.pageId()).update();
            jdbc.sql("""
                            INSERT INTO share_links (id, page_id, token_hash, level, expires_at, created_by, created_at)
                            VALUES (:id, :pageId, :tokenHash, :level, :expiresAt, :createdBy, :createdAt)
                            """)
                    .param("id", link.id())
                    .param("pageId", link.pageId())
                    .param("tokenHash", tokenHash)
                    .param("level", link.level().name())
                    .param("expiresAt", timestamp(link.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("createdBy", link.createdBy())
                    .param("createdAt", Timestamp.from(link.createdAt()))
                    .update();
        });
    }

    @Override
    public boolean update(UUID pageId, PageLevel level, Instant expiresAt) {
        return jdbc.sql("UPDATE share_links SET level = :level, expires_at = :expiresAt WHERE page_id = :pageId")
                .param("pageId", pageId)
                .param("level", level.name())
                .param("expiresAt", timestamp(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    @Override
    public Optional<ShareLink> findByPage(UUID pageId) {
        return find("page_id = :value", pageId);
    }

    @Override
    public Optional<ShareLink> findByTokenHash(String tokenHash) {
        return find("token_hash = :value", tokenHash);
    }

    @Override
    public Optional<ShareLink> findById(UUID id) {
        return find("id = :value", id);
    }

    private Optional<ShareLink> find(String condition, Object value) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM share_links WHERE " + condition)
                .param("value", value)
                .query((rs, row) -> link(rs))
                .optional();
    }

    @Override
    public boolean delete(UUID pageId) {
        return jdbc.sql("DELETE FROM share_links WHERE page_id = :pageId").param("pageId", pageId).update() == 1;
    }

    @Override
    public boolean trashedOrMissing(UUID pageId) {
        // 조상으로 올라가는 재귀는 기본 키로 찾는다.
        Boolean visible = jdbc.sql("""
                        WITH RECURSIVE up (id, parent_id, archived, depth) AS (
                            SELECT id, parent_id, archived_at IS NOT NULL, 0 FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, p.parent_id, p.archived_at IS NOT NULL, u.depth + 1
                            FROM pages p JOIN up u ON p.id = u.parent_id
                            WHERE u.depth < 1000
                        )
                        SELECT EXISTS (SELECT 1 FROM up) AND NOT EXISTS (SELECT 1 FROM up WHERE archived)
                        """)
                .param("pageId", pageId)
                .query(Boolean.class)
                .single();
        return !Boolean.TRUE.equals(visible);
    }

    @Override
    public String title(UUID pageId) {
        return jdbc.sql("SELECT title FROM pages WHERE id = :pageId")
                .param("pageId", pageId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static ShareLink link(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new ShareLink(
                rs.getObject("id", UUID.class),
                rs.getObject("page_id", UUID.class),
                PageLevel.valueOf(rs.getString("level")),
                expires == null ? null : expires.toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
