package com.copage.permission.out;

import com.copage.permission.app.GrantRepository;
import com.copage.permission.domain.PageLevel;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** page_grants 와 pages.restricted (docs/06-DATA-MODEL.md 8.5). */
public final class JdbcGrantRepository implements GrantRepository {

    private final JdbcClient jdbc;

    public JdbcGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<GrantRow> grantsOn(Collection<UUID> pageIds) {
        if (pageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT page_id, user_id, level FROM page_grants WHERE page_id = ANY(:ids)")
                .param("ids", pageIds.toArray(UUID[]::new))
                .query((rs, row) -> new GrantRow(
                        rs.getObject("page_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        PageLevel.valueOf(rs.getString("level"))))
                .list();
    }

    @Override
    public void upsert(UUID pageId, UUID userId, PageLevel level, UUID grantedBy) {
        jdbc.sql("""
                        INSERT INTO page_grants (page_id, user_id, level, granted_by)
                        VALUES (:pageId, :userId, :level, :grantedBy)
                        ON CONFLICT (page_id, user_id)
                        DO UPDATE SET level = EXCLUDED.level, granted_by = EXCLUDED.granted_by, created_at = now()
                        """)
                .param("pageId", pageId)
                .param("userId", userId)
                .param("level", level.name())
                .param("grantedBy", grantedBy)
                .update();
    }

    @Override
    public boolean delete(UUID pageId, UUID userId) {
        return jdbc.sql("DELETE FROM page_grants WHERE page_id = :pageId AND user_id = :userId")
                .param("pageId", pageId)
                .param("userId", userId)
                .update() > 0;
    }

    @Override
    public void setRestricted(UUID pageId, boolean restricted) {
        jdbc.sql("UPDATE pages SET restricted = :restricted WHERE id = :pageId")
                .param("pageId", pageId)
                .param("restricted", restricted)
                .update();
    }

    @Override
    public Set<UUID> subtree(UUID pageId) {
        // 하위로 내려가는 재귀는 pages_parent 인덱스를 쓴다.
        return new HashSet<>(jdbc.sql("""
                        WITH RECURSIVE tree (id, depth) AS (
                            SELECT id, 0 FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, t.depth + 1 FROM pages p JOIN tree t ON p.parent_id = t.id
                            WHERE t.depth < 1000
                        )
                        SELECT id FROM tree
                        """)
                .param("pageId", pageId)
                .query(UUID.class)
                .list());
    }

    @Override
    public Set<UUID> workspacePages(UUID workspaceId) {
        return new HashSet<>(jdbc.sql("SELECT id FROM pages WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .list());
    }

    @Override
    public int deleteAllInWorkspace(UUID workspaceId, UUID userId) {
        return jdbc.sql("""
                        DELETE FROM page_grants g USING pages p
                        WHERE g.page_id = p.id AND p.workspace_id = :workspaceId AND g.user_id = :userId
                        """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .update();
    }
}
