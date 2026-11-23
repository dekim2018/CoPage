package com.copage.workspace.out;

import com.copage.ot.FractionalIndex;
import com.copage.workspace.app.WorkspaceException;
import com.copage.workspace.app.WorkspaceException.Kind;
import com.copage.workspace.app.WorkspaceRepository;
import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** workspaces, workspace_members, pages 테이블 (docs/06-DATA-MODEL.md 8.5, 8.6). */
public final class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final String PAGE_COLUMNS =
            "p.id, p.workspace_id, p.parent_id, p.pos, p.title, p.created_by, p.created_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcWorkspaceRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void create(Workspace workspace) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO workspaces (id, name, owner_id, created_at)
                            VALUES (:id, :name, :ownerId, :createdAt)
                            """)
                    .param("id", workspace.id())
                    .param("name", workspace.name())
                    .param("ownerId", workspace.ownerId())
                    .param("createdAt", Timestamp.from(workspace.createdAt()))
                    .update();
            jdbc.sql("""
                            INSERT INTO workspace_members (workspace_id, user_id, role, created_at)
                            VALUES (:workspaceId, :userId, 'OWNER', :createdAt)
                            """)
                    .param("workspaceId", workspace.id())
                    .param("userId", workspace.ownerId())
                    .param("createdAt", Timestamp.from(workspace.createdAt()))
                    .update();
        });
    }

    @Override
    public List<Membership> findByMember(UUID userId) {
        return jdbc.sql("""
                        SELECT w.id, w.name, w.owner_id, w.created_at, m.role
                        FROM workspace_members m
                        JOIN workspaces w ON w.id = m.workspace_id
                        WHERE m.user_id = :userId
                        ORDER BY w.created_at, w.id
                        """)
                .param("userId", userId)
                .query((rs, row) -> new Membership(
                        new Workspace(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getObject("owner_id", UUID.class),
                                rs.getTimestamp("created_at").toInstant()),
                        WorkspaceRole.valueOf(rs.getString("role"))))
                .list();
    }

    @Override
    public Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId) {
        return jdbc.sql("SELECT role FROM workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId")
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query((rs, row) -> WorkspaceRole.valueOf(rs.getString("role")))
                .optional();
    }

    @Override
    public List<Member> members(UUID workspaceId) {
        return jdbc.sql("SELECT user_id, role FROM workspace_members WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query((rs, row) -> new Member(
                        rs.getObject("user_id", UUID.class), WorkspaceRole.valueOf(rs.getString("role"))))
                .list();
    }

    @Override
    public boolean addMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
        return jdbc.sql("""
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        VALUES (:workspaceId, :userId, :role)
                        ON CONFLICT DO NOTHING
                        """)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .param("role", role.name())
                .update() == 1;
    }

    @Override
    public Optional<Page> findPage(UUID workspaceId, UUID pageId) {
        return jdbc.sql("SELECT " + PAGE_COLUMNS + """

                        FROM pages p
                        WHERE p.id = :pageId AND p.workspace_id = :workspaceId AND p.archived_at IS NULL
                        """)
                .param("pageId", pageId)
                .param("workspaceId", workspaceId)
                .query((rs, row) -> page(rs))
                .optional();
    }

    @Override
    public List<PageSummary> children(UUID workspaceId, UUID parentId) {
        // NULL 과 비교할 수 없으므로 최상위와 하위의 조건을 나눈다. 바인딩한 NULL 은 타입을 알 수 없어 IS NOT DISTINCT FROM 도
        // 쓰지 못한다.
        String sql = "SELECT " + PAGE_COLUMNS + """
                        , EXISTS (
                            SELECT 1 FROM pages c
                            WHERE c.workspace_id = p.workspace_id AND c.parent_id = p.id AND c.archived_at IS NULL
                        ) AS has_children
                        FROM pages p
                        WHERE p.workspace_id = :workspaceId AND p.archived_at IS NULL
                        """ + " AND " + parentCondition("p", parentId) + " ORDER BY p.pos, p.id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("workspaceId", workspaceId);
        if (parentId != null) {
            statement = statement.param("parentId", parentId);
        }
        return statement.query((rs, row) -> new PageSummary(page(rs), rs.getBoolean("has_children"))).list();
    }

    @Override
    public Page createPage(
            UUID workspaceId, UUID pageId, UUID parentId, UUID afterId, UUID createdBy, Instant createdAt) {
        return tx.execute(status -> {
            // 같은 워크스페이스의 트리 변경을 줄 세운다. 동시에 같은 자리에 만들어도 같은 순서 키가 나오지 않는다 (8.6).
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:workspaceId, 0))")
                    .param("workspaceId", workspaceId.toString())
                    .query((rs, row) -> 1)
                    .list();
            if (parentId != null && findPage(workspaceId, parentId).isEmpty()) {
                throw new WorkspaceException(Kind.NOT_FOUND, "부모 페이지가 없다");
            }
            // 보관된 형제도 자리를 차지한다. 나중에 복원해도 순서 키가 겹치지 않는다.
            String before;
            String after;
            if (afterId == null) {
                before = siblingValue("max(p.pos)", workspaceId, parentId, "TRUE", Map.of());
                after = null;
            } else {
                before = siblingValue("p.pos", workspaceId, parentId, "p.id = :afterId AND p.archived_at IS NULL",
                        Map.of("afterId", afterId));
                if (before == null) {
                    throw new WorkspaceException(Kind.BAD_REQUEST, "afterId 가 그 부모의 하위 페이지가 아니다");
                }
                after = siblingValue("min(p.pos)", workspaceId, parentId, "p.pos > :afterPos",
                        Map.of("afterPos", before));
            }
            String pos = FractionalIndex.between(before, after);
            jdbc.sql("""
                            INSERT INTO pages (id, workspace_id, parent_id, pos, created_by, created_at)
                            VALUES (:id, :workspaceId, :parentId, :pos, :createdBy, :createdAt)
                            """)
                    .param("id", pageId)
                    .param("workspaceId", workspaceId)
                    .param("parentId", parentId, Types.OTHER)
                    .param("pos", pos)
                    .param("createdBy", createdBy)
                    .param("createdAt", Timestamp.from(createdAt))
                    .update();
            return new Page(pageId, workspaceId, parentId, pos, "", createdBy, createdAt);
        });
    }

    /** parentId 의 하위 페이지들(보관된 것 포함)에서 condition 에 맞는 expression 값을 하나 읽는다. 없으면 null. */
    private String siblingValue(
            String expression, UUID workspaceId, UUID parentId, String condition, Map<String, Object> params) {
        Map<String, Object> all = new HashMap<>(params);
        all.put("workspaceId", workspaceId);
        if (parentId != null) {
            all.put("parentId", parentId);
        }
        List<String> values = jdbc.sql("SELECT " + expression + " FROM pages p WHERE p.workspace_id = :workspaceId AND "
                        + parentCondition("p", parentId) + " AND " + condition)
                .params(all)
                .query((rs, row) -> rs.getString(1))
                .list();
        return values.isEmpty() ? null : values.get(0);
    }

    private static String parentCondition(String alias, UUID parentId) {
        return parentId == null ? alias + ".parent_id IS NULL" : alias + ".parent_id = :parentId";
    }

    private static Page page(ResultSet rs) throws SQLException {
        return new Page(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("pos"),
                rs.getString("title"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
