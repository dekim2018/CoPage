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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** workspaces, workspace_members, pages 테이블 (docs/06-DATA-MODEL.md 8.5, 8.6). */
public final class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final String PAGE_COLUMNS =
            "p.id, p.workspace_id, p.parent_id, p.pos, p.title, p.restricted, p.created_by, p.created_at";

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
    public Optional<Workspace> findWorkspace(UUID workspaceId) {
        return jdbc.sql("SELECT id, name, owner_id, created_at FROM workspaces WHERE id = :id")
                .param("id", workspaceId)
                .query((rs, row) -> new Workspace(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
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
    public MemberUpdate changeRole(UUID workspaceId, UUID userId, WorkspaceRole role) {
        return tx.execute(status -> {
            MemberUpdate check = checkLastOwner(workspaceId, userId, role);
            if (check != MemberUpdate.UPDATED) {
                return check;
            }
            jdbc.sql("UPDATE workspace_members SET role = :role WHERE workspace_id = :workspaceId AND user_id = :userId")
                    .param("role", role.name())
                    .param("workspaceId", workspaceId)
                    .param("userId", userId)
                    .update();
            return MemberUpdate.UPDATED;
        });
    }

    @Override
    public MemberUpdate removeMember(UUID workspaceId, UUID userId) {
        return tx.execute(status -> {
            MemberUpdate check = checkLastOwner(workspaceId, userId, null);
            if (check != MemberUpdate.UPDATED) {
                return check;
            }
            jdbc.sql("DELETE FROM workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId")
                    .param("workspaceId", workspaceId)
                    .param("userId", userId)
                    .update();
            return MemberUpdate.UPDATED;
        });
    }

    /** 트랜잭션 안에서 부른다. next 가 null 이면 내보내기다. 두 OWNER 가 서로를 동시에 내리는 경우도 잠금이 줄 세운다. */
    private MemberUpdate checkLastOwner(UUID workspaceId, UUID userId, WorkspaceRole next) {
        lockWorkspace(workspaceId);
        Optional<WorkspaceRole> current = roleOf(workspaceId, userId);
        if (current.isEmpty()) {
            return MemberUpdate.NOT_MEMBER;
        }
        if (current.get() == WorkspaceRole.OWNER && next != WorkspaceRole.OWNER) {
            int owners = jdbc.sql("SELECT count(*) FROM workspace_members WHERE workspace_id = :workspaceId AND role = 'OWNER'")
                    .param("workspaceId", workspaceId)
                    .query(Integer.class)
                    .single();
            if (owners <= 1) {
                return MemberUpdate.LAST_OWNER;
            }
        }
        return MemberUpdate.UPDATED;
    }

    private void lockWorkspace(UUID workspaceId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:workspaceId, 0))")
                .param("workspaceId", workspaceId.toString())
                .query((rs, row) -> 1)
                .list();
    }

    @Override
    public boolean updateTitle(UUID pageId, String title, long rev) {
        return jdbc.sql("UPDATE pages SET title = :title, title_rev = :rev WHERE id = :pageId AND title_rev < :rev")
                .param("pageId", pageId)
                .param("title", title)
                .param("rev", rev)
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
    public Optional<Page> findPageById(UUID pageId) {
        return jdbc.sql("SELECT " + PAGE_COLUMNS + " FROM pages p WHERE p.id = :pageId AND p.archived_at IS NULL")
                .param("pageId", pageId)
                .query((rs, row) -> page(rs))
                .optional();
    }

    @Override
    public List<Page> findPages(UUID workspaceId, Collection<UUID> pageIds) {
        if (pageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + PAGE_COLUMNS + """

                        FROM pages p
                        WHERE p.id = ANY(:ids) AND p.workspace_id = :workspaceId AND p.archived_at IS NULL
                        """)
                .param("ids", pageIds.toArray(UUID[]::new))
                .param("workspaceId", workspaceId)
                .query((rs, row) -> page(rs))
                .list();
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
            lockWorkspace(workspaceId);
            if (parentId != null && findPage(workspaceId, parentId).isEmpty()) {
                throw new WorkspaceException(Kind.NOT_FOUND, "부모 페이지가 없다");
            }
            String pos = positionAfter(workspaceId, parentId, afterId);
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
            return new Page(pageId, workspaceId, parentId, pos, "", false, createdBy, createdAt);
        });
    }

    /**
     * parentId 아래 afterId 바로 뒤(null 이면 맨 뒤)의 순서 키. 워크스페이스 잠금 안에서 부른다. 보관된 형제도 자리를 차지한다.
     * 나중에 복원해도 순서 키가 겹치지 않는다.
     */
    private String positionAfter(UUID workspaceId, UUID parentId, UUID afterId) {
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
            after = siblingValue("min(p.pos)", workspaceId, parentId, "p.pos > :afterPos", Map.of("afterPos", before));
        }
        return FractionalIndex.between(before, after);
    }

    @Override
    public Page movePage(UUID workspaceId, UUID pageId, UUID newParentId, UUID afterId) {
        return movePage(workspaceId, pageId, newParentId, afterId, false);
    }

    @Override
    public Page movePage(UUID workspaceId, UUID pageId, UUID newParentId, UUID afterId, boolean first) {
        return tx.execute(status -> {
            lockWorkspace(workspaceId);
            Page page = findPage(workspaceId, pageId)
                    .orElseThrow(() -> new WorkspaceException(Kind.NOT_FOUND, "페이지가 없다"));
            if (newParentId != null) {
                if (findPage(workspaceId, newParentId).isEmpty()) {
                    throw new WorkspaceException(Kind.NOT_FOUND, "새 부모 페이지가 없다");
                }
                if (isSelfOrDescendant(newParentId, pageId)) {
                    throw new WorkspaceException(Kind.BAD_REQUEST, "페이지를 자기 자신이나 하위 페이지 아래로 옮길 수 없다");
                }
            }
            if (pageId.equals(afterId)) {
                throw new WorkspaceException(Kind.BAD_REQUEST, "afterId 가 옮기는 페이지 자신이다");
            }
            // 맨 앞: 옮기는 페이지 자신을 뺀 형제 중 가장 앞의 앞.
            String pos = first
                    ? FractionalIndex.between(null, siblingValue("min(p.pos)", workspaceId, newParentId,
                            "p.id <> :pageId", Map.of("pageId", pageId)))
                    : positionAfter(workspaceId, newParentId, afterId);
            jdbc.sql("UPDATE pages SET parent_id = :parentId, pos = :pos WHERE id = :pageId")
                    .param("pageId", pageId)
                    .param("parentId", newParentId, Types.OTHER)
                    .param("pos", pos)
                    .update();
            return new Page(pageId, workspaceId, newParentId, pos, page.title(), page.restricted(), page.createdBy(),
                    page.createdAt());
        });
    }

    /** candidate 가 ancestorId 자신이거나 그 하위인지. candidate 에서 위로 거슬러 올라간다(기본 키로 찾는다). */
    private boolean isSelfOrDescendant(UUID candidate, UUID ancestorId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        WITH RECURSIVE up (id, parent_id, depth) AS (
                            SELECT id, parent_id, 0 FROM pages WHERE id = :candidate
                            UNION ALL
                            SELECT p.id, p.parent_id, u.depth + 1 FROM pages p JOIN up u ON p.id = u.parent_id
                            WHERE u.depth < 1000
                        )
                        SELECT EXISTS (SELECT 1 FROM up WHERE id = :ancestorId)
                        """)
                .param("candidate", candidate)
                .param("ancestorId", ancestorId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean archive(UUID workspaceId, UUID pageId, Instant archivedAt) {
        return jdbc.sql("""
                        UPDATE pages SET archived_at = :archivedAt
                        WHERE id = :pageId AND workspace_id = :workspaceId AND archived_at IS NULL
                        """)
                .param("pageId", pageId)
                .param("workspaceId", workspaceId)
                .param("archivedAt", Timestamp.from(archivedAt))
                .update() == 1;
    }

    @Override
    public Optional<TrashedPage> findTrashed(UUID workspaceId, UUID pageId) {
        return jdbc.sql("SELECT " + PAGE_COLUMNS + """
                        , p.archived_at
                        FROM pages p
                        WHERE p.id = :pageId AND p.workspace_id = :workspaceId AND p.archived_at IS NOT NULL
                        """)
                .param("pageId", pageId)
                .param("workspaceId", workspaceId)
                .query((rs, row) -> new TrashedPage(page(rs), rs.getTimestamp("archived_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<Page> restore(UUID workspaceId, UUID pageId) {
        return tx.execute(status -> {
            lockWorkspace(workspaceId);
            Optional<TrashedPage> trashed = findTrashed(workspaceId, pageId);
            if (trashed.isEmpty()) {
                return Optional.<Page>empty();
            }
            Page page = trashed.get().page();
            UUID parentId = page.parentId();
            String pos = page.pos();
            if (parentId != null && hasArchivedAncestor(parentId)) {
                parentId = null;
                pos = positionAfter(workspaceId, null, null);
            }
            jdbc.sql("UPDATE pages SET archived_at = NULL, parent_id = :parentId, pos = :pos WHERE id = :pageId")
                    .param("pageId", pageId)
                    .param("parentId", parentId, Types.OTHER)
                    .param("pos", pos)
                    .update();
            return Optional.of(new Page(pageId, workspaceId, parentId, pos, page.title(), page.restricted(),
                    page.createdBy(), page.createdAt()));
        });
    }

    /** pageId 자신이나 그 조상 중 휴지통에 있는 것이 있는지. */
    private boolean hasArchivedAncestor(UUID pageId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        WITH RECURSIVE up (id, parent_id, archived, depth) AS (
                            SELECT id, parent_id, archived_at IS NOT NULL, 0 FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, p.parent_id, p.archived_at IS NOT NULL, u.depth + 1
                            FROM pages p JOIN up u ON p.id = u.parent_id
                            WHERE u.depth < 1000
                        )
                        SELECT EXISTS (SELECT 1 FROM up WHERE archived)
                        """)
                .param("pageId", pageId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<TrashedPage> trash(UUID workspaceId) {
        return jdbc.sql("SELECT " + PAGE_COLUMNS + """
                        , p.archived_at
                        FROM pages p
                        WHERE p.workspace_id = :workspaceId AND p.archived_at IS NOT NULL
                        ORDER BY p.archived_at DESC, p.id
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, row) -> new TrashedPage(page(rs), rs.getTimestamp("archived_at").toInstant()))
                .list();
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
    public List<SearchHit> search(UUID workspaceId, String needle, int limit) {
        // pages.title 과 page_search.body 의 pg_trgm GIN 인덱스가 ILIKE '%..%' 를 받는다. 두 글자 이하 검색어는 세 글자 단위
        // 조각을 만들 수 없어 인덱스가 걸러 주지 못하고 다 훑는다(D-47). 제목과 본문을 OR 로 한 번에 거르면 두 표에 걸친 조건이라
        // 인덱스를 쓰지 못한다. 따로 찾아 합친다(UNION). 페이지 10,000 개에서 없는 네 글자 검색이 185ms 에서 8.6ms 가 되었다.
        String pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                        WITH hits AS (
                            SELECT id FROM pages WHERE workspace_id = :workspaceId AND title ILIKE :pattern
                            UNION
                            SELECT page_id FROM page_search WHERE body ILIKE :pattern
                        )
                        SELECT\s""" + PAGE_COLUMNS + """
                        , CASE WHEN s.body IS NULL THEN ''
                               ELSE substr(s.body, greatest(1, strpos(lower(s.body), lower(:needle)) - 40), 160)
                          END AS snippet
                        FROM hits h
                        JOIN pages p ON p.id = h.id
                        LEFT JOIN page_search s ON s.page_id = p.id
                        WHERE p.workspace_id = :workspaceId AND p.archived_at IS NULL
                        ORDER BY (p.title ILIKE :pattern) DESC, coalesce(s.updated_at, p.created_at) DESC, p.id
                        LIMIT :limit
                        """)
                .param("workspaceId", workspaceId)
                .param("needle", needle)
                .param("pattern", pattern)
                .param("limit", limit)
                .query((rs, row) -> new SearchHit(page(rs), rs.getString("snippet")))
                .list();
    }

    @Override
    public int depthOf(UUID pageId) {
        return jdbc.sql("""
                        WITH RECURSIVE up (id, parent_id, depth) AS (
                            SELECT id, parent_id, 1 FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, p.parent_id, u.depth + 1 FROM pages p JOIN up u ON p.id = u.parent_id
                            WHERE u.depth < 1000
                        )
                        SELECT coalesce(max(depth), 0) FROM up
                        """)
                .param("pageId", pageId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int heightOf(UUID pageId) {
        return jdbc.sql("""
                        WITH RECURSIVE down (id, depth) AS (
                            SELECT id, 1 FROM pages WHERE id = :pageId
                            UNION ALL
                            SELECT p.id, d.depth + 1 FROM pages p JOIN down d ON p.parent_id = d.id
                            WHERE d.depth < 1000
                        )
                        SELECT coalesce(max(depth), 0) FROM down
                        """)
                .param("pageId", pageId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void updateBody(UUID pageId, String body, long rev, Instant now) {
        jdbc.sql("""
                        INSERT INTO page_search (page_id, body, rev, updated_at)
                        SELECT id, :body, :rev, :now FROM pages WHERE id = :pageId
                        ON CONFLICT (page_id) DO UPDATE SET body = EXCLUDED.body, rev = EXCLUDED.rev,
                            updated_at = EXCLUDED.updated_at
                        WHERE page_search.rev < EXCLUDED.rev
                        """)
                .param("pageId", pageId)
                .param("body", body)
                .param("rev", rev)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public int purgeTrash(Instant archivedBefore) {
        // 한 문장이라 부모와 하위를 함께 지워도 parent_id 외래 키는 문장 끝에 확인된다. doc_ops, doc_snapshots, doc_versions
        // 는 외래 키가 없어(V3 이전 문서) 따로 지운다.
        return jdbc.sql("""
                        WITH RECURSIVE doomed (id, depth) AS (
                            SELECT id, 0 FROM pages WHERE archived_at < :before
                            UNION
                            SELECT p.id, d.depth + 1 FROM pages p JOIN doomed d ON p.parent_id = d.id
                            WHERE d.depth < 1000
                        ),
                        ops AS (DELETE FROM doc_ops WHERE doc_id IN (SELECT id FROM doomed)),
                        snapshots AS (DELETE FROM doc_snapshots WHERE doc_id IN (SELECT id FROM doomed)),
                        versions AS (DELETE FROM doc_versions WHERE doc_id IN (SELECT id FROM doomed))
                        DELETE FROM pages WHERE id IN (SELECT id FROM doomed)
                        """)
                .param("before", Timestamp.from(archivedBefore))
                .update();
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
                rs.getBoolean("restricted"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
