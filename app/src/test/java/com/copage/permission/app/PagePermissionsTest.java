package com.copage.permission.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.domain.PageLevel;
import com.copage.permission.out.JdbcPermissionRepository;
import com.copage.support.TestInfra;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import com.copage.workspace.out.JdbcWorkspaceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 권한 계산을 실제 PostgreSQL 의 트리에 대고 확인한다. 규칙 자체의 사례는 PermissionRulesTest 에 있다. */
class PagePermissionsTest {

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl(), TestInfra.POSTGRES.getUsername(), TestInfra.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final JdbcWorkspaceRepository workspaces =
            new JdbcWorkspaceRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    private final PagePermissions permissions = new PagePermissions(new JdbcPermissionRepository(jdbc));
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private final UUID owner = user();
    private final UUID member = user();
    private final UUID guest = user();
    private final UUID stranger = user();
    private final UUID workspaceId = UUID.randomUUID();

    // root ─ child ─ grandchild, 그리고 최상위 형제 sibling
    private final UUID root;
    private final UUID child;
    private final UUID grandchild;
    private final UUID sibling;

    PagePermissionsTest() {
        workspaces.create(new Workspace(workspaceId, "팀", owner, now));
        workspaces.addMember(workspaceId, member, WorkspaceRole.MEMBER);
        workspaces.addMember(workspaceId, guest, WorkspaceRole.GUEST);
        root = page(null);
        child = page(root);
        grandchild = page(child);
        sibling = page(null);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash, name) VALUES (:id, :email, 'x', 'a')")
                .param("id", id)
                .param("email", "u-" + id + "@example.com")
                .update();
        return id;
    }

    private UUID page(UUID parentId) {
        return workspaces.createPage(workspaceId, UUID.randomUUID(), parentId, null, owner, now).id();
    }

    private void grant(UUID pageId, UUID userId, PageLevel level) {
        jdbc.sql("INSERT INTO page_grants (page_id, user_id, level, granted_by) VALUES (:p, :u, :l, :by)")
                .param("p", pageId).param("u", userId).param("l", level.name()).param("by", owner)
                .update();
    }

    private void restrict(UUID pageId) {
        jdbc.sql("UPDATE pages SET restricted = true WHERE id = :id").param("id", pageId).update();
    }

    private PageLevel level(UUID userId, UUID pageId) {
        return permissions.access(userId, pageId) instanceof Access.Granted granted ? granted.level() : null;
    }

    @Test
    void 없는_페이지와_속하지_않은_사람은_구분하지_않고_NotFound() {
        assertInstanceOf(Access.NotFound.class, permissions.access(member, UUID.randomUUID()));
        assertInstanceOf(Access.NotFound.class, permissions.access(stranger, grandchild));
        // 다른 워크스페이스의 페이지에 권한을 받았어도 그 워크스페이스에 속하지 않으면 마찬가지다.
        grant(grandchild, stranger, PageLevel.FULL);
        assertInstanceOf(Access.NotFound.class, permissions.access(stranger, grandchild));
    }

    @Test
    void 역할의_기본_권한과_조상에서_이어지는_부여() {
        assertEquals(PageLevel.FULL, level(owner, grandchild));
        assertEquals(PageLevel.EDIT, level(member, grandchild));
        assertInstanceOf(Access.Denied.class, permissions.access(guest, grandchild));

        grant(root, guest, PageLevel.COMMENT);
        assertEquals(PageLevel.COMMENT, level(guest, grandchild));
        assertInstanceOf(Access.Denied.class, permissions.access(guest, sibling), "다른 가지로는 이어지지 않는다");
    }

    @Test
    void 제한된_페이지는_경계_위의_부여와_기본_권한을_끊는다() {
        grant(root, guest, PageLevel.EDIT);
        restrict(child);

        assertInstanceOf(Access.Denied.class, permissions.access(member, grandchild));
        assertInstanceOf(Access.Denied.class, permissions.access(guest, grandchild));
        assertEquals(PageLevel.EDIT, level(member, root), "경계 위는 그대로다");
        assertEquals(PageLevel.FULL, level(owner, grandchild));

        grant(child, member, PageLevel.VIEW);
        assertEquals(PageLevel.VIEW, level(member, grandchild));
    }

    @Test
    void 조상이_휴지통에_있으면_하위도_없는_것으로_본다() {
        jdbc.sql("UPDATE pages SET archived_at = now() WHERE id = :id").param("id", root).update();

        assertInstanceOf(Access.NotFound.class, permissions.access(owner, grandchild));
        assertEquals(PageLevel.EDIT, level(member, sibling));
    }

    @Test
    void 경로에는_볼_수_있는_조상만_루트_쪽부터_담는다() {
        Access.Granted forMember = (Access.Granted) permissions.access(member, grandchild);
        assertEquals(List.of(root, child), forMember.visibleAncestors());

        // 하위 페이지만 공유받은 게스트는 조상을 볼 수 없다.
        grant(grandchild, guest, PageLevel.VIEW);
        assertEquals(List.of(), ((Access.Granted) permissions.access(guest, grandchild)).visibleAncestors());
        grant(child, guest, PageLevel.VIEW);
        assertEquals(List.of(child), ((Access.Granted) permissions.access(guest, grandchild)).visibleAncestors());
    }

    @Test
    void 하위_목록의_등급은_부모_사슬에_한_칸씩_붙여_계산하고_볼_수_없는_페이지는_뺀다() {
        UUID hidden = page(root);
        restrict(hidden);
        grant(sibling, guest, PageLevel.VIEW);

        assertEquals(Map.of(child, PageLevel.EDIT),
                permissions.childLevels(member, workspaceId, root, List.of(child, hidden)));
        assertEquals(Map.of(child, PageLevel.FULL, hidden, PageLevel.FULL),
                permissions.childLevels(owner, workspaceId, root, List.of(child, hidden)));
        assertEquals(Map.of(sibling, PageLevel.VIEW),
                permissions.childLevels(guest, workspaceId, null, List.of(root, sibling)));
        assertEquals(Map.of(), permissions.childLevels(stranger, workspaceId, null, List.of(root, sibling)));
        // 각 결과는 페이지 하나씩 계산한 것과 같다.
        assertEquals(level(member, child), permissions.childLevels(member, workspaceId, root, List.of(child)).get(child));
    }

    @Test
    void 새_페이지를_만들_수_있는_등급() {
        assertEquals(PageLevel.EDIT, permissions.levelForNewChild(member, workspaceId, null));
        assertNull(permissions.levelForNewChild(guest, workspaceId, null));
        grant(child, guest, PageLevel.EDIT);
        assertEquals(PageLevel.EDIT, permissions.levelForNewChild(guest, workspaceId, child));
        // 다른 워크스페이스 ID 로 물으면 그 부모는 없는 것이다.
        assertNull(permissions.levelForNewChild(member, UUID.randomUUID(), child));
    }
}
