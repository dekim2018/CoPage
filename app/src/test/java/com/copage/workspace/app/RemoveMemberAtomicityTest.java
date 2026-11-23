package com.copage.workspace.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copage.identity.out.JdbcUserDirectory;
import com.copage.permission.app.GrantRepository;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.domain.PageLevel;
import com.copage.permission.out.JdbcGrantRepository;
import com.copage.permission.out.JdbcPermissionRepository;
import com.copage.support.TestInfra;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import com.copage.workspace.out.JdbcWorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 내보내기에서 멤버 삭제와 권한 삭제가 함께 커밋되거나 함께 취소되는지 (D-16). */
class RemoveMemberAtomicityTest {

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl(), TestInfra.POSTGRES.getUsername(), TestInfra.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    private final JdbcWorkspaceRepository workspaces = new JdbcWorkspaceRepository(jdbc, tx);

    /** 권한을 지우는 단계에서 실패하는 저장소. */
    private final class FailingGrants implements GrantRepository {
        private final JdbcGrantRepository real = new JdbcGrantRepository(jdbc);

        @Override
        public List<GrantRow> grantsOn(Collection<UUID> pageIds) {
            return real.grantsOn(pageIds);
        }

        @Override
        public void upsert(UUID pageId, UUID userId, PageLevel level, UUID grantedBy) {
            real.upsert(pageId, userId, level, grantedBy);
        }

        @Override
        public boolean delete(UUID pageId, UUID userId) {
            return real.delete(pageId, userId);
        }

        @Override
        public void setRestricted(UUID pageId, boolean restricted) {
            real.setRestricted(pageId, restricted);
        }

        @Override
        public Set<UUID> subtree(UUID pageId) {
            return real.subtree(pageId);
        }

        @Override
        public int deleteAllInWorkspace(UUID workspaceId, UUID userId) {
            throw new IllegalStateException("DB 가 끊겼다");
        }
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash, name) VALUES (:id, :email, 'x', 'a')")
                .param("id", id)
                .param("email", "u-" + id + "@example.com")
                .update();
        return id;
    }

    @Test
    void 권한을_지우다_실패하면_멤버도_그대로다() {
        UUID owner = user();
        UUID member = user();
        UUID workspaceId = UUID.randomUUID();
        workspaces.create(new Workspace(workspaceId, "팀", owner, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        workspaces.addMember(workspaceId, member, WorkspaceRole.MEMBER);
        PagePermissions permissions = new PagePermissions(new JdbcPermissionRepository(jdbc));
        PageGrants grants = new PageGrants(permissions, new JdbcPermissionRepository(jdbc), new FailingGrants(),
                (ws, u) -> { }, new JdbcUserDirectory(jdbc), change -> { });
        WorkspaceService service = new WorkspaceService(workspaces, new JdbcUserDirectory(jdbc), permissions, grants,
                work -> tx.executeWithoutResult(status -> work.run()), Clock.systemUTC());

        assertThrows(IllegalStateException.class, () -> service.removeMember(owner, workspaceId, member));

        assertEquals(WorkspaceRole.MEMBER, workspaces.roleOf(workspaceId, member).orElseThrow());
    }
}
