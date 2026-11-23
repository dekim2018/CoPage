package com.copage.permission.out;

import com.copage.permission.app.PermissionRepository;
import com.copage.permission.domain.PageLevel;
import com.copage.permission.domain.PermissionRules.Link;
import com.copage.permission.domain.PermissionRules.Role;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** pages, workspace_members, page_grants 를 읽는다 (docs/06-DATA-MODEL.md 8.5). */
public final class JdbcPermissionRepository implements PermissionRepository {

    /** 트리 깊이 한도. 이동 검사로 순환은 생기지 않지만, 생기더라도 질의가 끝나게 한다. */
    private static final int MAX_DEPTH = 1000;

    private final JdbcClient jdbc;

    public JdbcPermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Chain chain(UUID userId, UUID pageId) {
        record Row(UUID pageId, UUID workspaceId, boolean restricted, boolean archived, String grant, String role) {
        }
        List<Row> rows = jdbc.sql("""
                        WITH RECURSIVE chain (id, parent_id, workspace_id, restricted, archived, depth) AS (
                            SELECT id, parent_id, workspace_id, restricted, archived_at IS NOT NULL, 0
                            FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, p.parent_id, p.workspace_id, p.restricted, p.archived_at IS NOT NULL, c.depth + 1
                            FROM pages p JOIN chain c ON p.id = c.parent_id
                            WHERE c.depth < :maxDepth
                        )
                        SELECT c.id, c.workspace_id, c.restricted, c.archived, g.level, m.role
                        FROM chain c
                        LEFT JOIN page_grants g ON g.page_id = c.id AND g.user_id = :userId
                        LEFT JOIN workspace_members m ON m.workspace_id = c.workspace_id AND m.user_id = :userId
                        ORDER BY c.depth
                        """)
                .param("pageId", pageId)
                .param("userId", userId)
                .param("maxDepth", MAX_DEPTH)
                .query((rs, row) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getBoolean("restricted"),
                        rs.getBoolean("archived"),
                        rs.getString("level"),
                        rs.getString("role")))
                .list();
        if (rows.isEmpty()) {
            return null;
        }
        List<ChainRow> chain = new ArrayList<>(rows.size());
        for (Row row : rows) {
            chain.add(new ChainRow(row.pageId(), row.restricted(), row.archived(), level(row.grant())));
        }
        Row first = rows.get(0);
        return new Chain(first.workspaceId(), first.role() == null ? null : Role.valueOf(first.role()), chain);
    }

    @Override
    public Role role(UUID workspaceId, UUID userId) {
        return jdbc.sql("SELECT role FROM workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId")
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query((rs, row) -> Role.valueOf(rs.getString("role")))
                .optional()
                .orElse(null);
    }

    @Override
    public Map<UUID, Link> links(UUID userId, Collection<UUID> pageIds) {
        if (pageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Link> links = new HashMap<>();
        jdbc.sql("""
                        SELECT p.id, p.restricted, g.level
                        FROM pages p
                        LEFT JOIN page_grants g ON g.page_id = p.id AND g.user_id = :userId
                        WHERE p.id = ANY(:ids)
                        """)
                .param("userId", userId)
                .param("ids", pageIds.toArray(UUID[]::new))
                .query(rs -> {
                    links.put(rs.getObject("id", UUID.class),
                            new Link(rs.getBoolean("restricted"), level(rs.getString("level"))));
                });
        return links;
    }

    private static PageLevel level(String value) {
        return value == null ? null : PageLevel.valueOf(value);
    }
}
