package com.copage.workspace.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.support.TestInfra;
import com.copage.workspace.app.WorkspaceException;
import com.copage.workspace.app.WorkspaceRepository.MemberUpdate;
import com.copage.workspace.app.WorkspaceRepository.PageSummary;
import com.copage.workspace.domain.Page;
import com.copage.workspace.domain.Workspace;
import com.copage.workspace.domain.WorkspaceRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 워크스페이스와 페이지 트리 저장소를 실제 PostgreSQL 에 대고 확인한다. */
class JdbcWorkspaceRepositoryTest {

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl(), TestInfra.POSTGRES.getUsername(), TestInfra.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final JdbcWorkspaceRepository repository =
            new JdbcWorkspaceRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private final UUID owner = user();
    private final Workspace workspace = new Workspace(UUID.randomUUID(), "팀", owner, now);

    JdbcWorkspaceRepositoryTest() {
        repository.create(workspace);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash, name) VALUES (:id, :email, 'x', '이름')")
                .param("id", id)
                .param("email", "u-" + id + "@example.com")
                .update();
        return id;
    }

    private Page create(UUID parentId, UUID afterId) {
        return repository.createPage(workspace.id(), UUID.randomUUID(), parentId, afterId, owner, now);
    }

    private List<UUID> children(UUID parentId) {
        return repository.children(workspace.id(), parentId).stream().map(summary -> summary.page().id()).toList();
    }

    @Test
    void 만든_사람은_OWNER_이고_다른_사람은_속하지_않는다() {
        assertEquals(WorkspaceRole.OWNER, repository.roleOf(workspace.id(), owner).orElseThrow());
        assertTrue(repository.roleOf(workspace.id(), user()).isEmpty());
        assertEquals(workspace, repository.findByMember(owner).get(0).workspace());
    }

    @Test
    void 이미_멤버면_다시_넣지_않는다() {
        UUID member = user();

        assertTrue(repository.addMember(workspace.id(), member, WorkspaceRole.MEMBER));
        assertFalse(repository.addMember(workspace.id(), member, WorkspaceRole.GUEST));
        assertEquals(WorkspaceRole.MEMBER, repository.roleOf(workspace.id(), member).orElseThrow());
    }

    @Test
    void 맨_뒤에_붙이거나_지정한_형제_바로_뒤에_넣는다() {
        Page first = create(null, null);
        Page third = create(null, null);
        Page second = create(null, first.id());
        Page fourth = create(null, third.id());

        assertEquals(List.of(first.id(), second.id(), third.id(), fourth.id()), children(null));
    }

    @Test
    void 하위_페이지는_부모_아래에만_보이고_부모는_하위가_있다고_알린다() {
        Page parent = create(null, null);
        Page child = create(parent.id(), null);

        assertEquals(List.of(parent.id()), children(null));
        assertEquals(List.of(child.id()), children(parent.id()));
        PageSummary summary = repository.children(workspace.id(), null).get(0);
        assertTrue(summary.hasChildren());
        assertFalse(repository.children(workspace.id(), parent.id()).get(0).hasChildren());
    }

    @Test
    void 보관된_페이지는_목록과_부모_후보에서_빠지지만_순서_자리는_지킨다() {
        Page kept = create(null, null);
        Page archived = create(null, null);
        jdbc.sql("UPDATE pages SET archived_at = now() WHERE id = :id").param("id", archived.id()).update();

        assertEquals(List.of(kept.id()), children(null));
        assertThrows(WorkspaceException.class, () -> create(archived.id(), null));
        // 맨 뒤에 붙인 새 페이지는 보관된 페이지보다도 뒤다. 복원해도 키가 겹치지 않는다.
        Page next = create(null, null);
        assertTrue(next.pos().compareTo(archived.pos()) > 0);
    }

    @Test
    void 다른_워크스페이스의_페이지는_부모나_앞_형제가_될_수_없다() {
        Workspace other = new Workspace(UUID.randomUUID(), "다른 팀", owner, now);
        repository.create(other);
        Page foreign = repository.createPage(other.id(), UUID.randomUUID(), null, null, owner, now);
        Page parent = create(null, null);

        WorkspaceException noParent = assertThrows(WorkspaceException.class, () -> create(foreign.id(), null));
        assertEquals(WorkspaceException.Kind.NOT_FOUND, noParent.kind());
        WorkspaceException badAfter = assertThrows(WorkspaceException.class, () -> create(null, foreign.id()));
        assertEquals(WorkspaceException.Kind.BAD_REQUEST, badAfter.kind());
        // 같은 워크스페이스라도 다른 부모의 하위는 앞 형제가 아니다.
        Page child = create(parent.id(), null);
        assertThrows(WorkspaceException.class, () -> create(null, child.id()));
    }

    @Test
    void 마지막_OWNER_는_내리거나_내보낼_수_없다() {
        UUID member = user();
        repository.addMember(workspace.id(), member, WorkspaceRole.MEMBER);

        assertEquals(MemberUpdate.LAST_OWNER, repository.changeRole(workspace.id(), owner, WorkspaceRole.MEMBER));
        assertEquals(MemberUpdate.LAST_OWNER, repository.removeMember(workspace.id(), owner));
        assertEquals(MemberUpdate.NOT_MEMBER, repository.removeMember(workspace.id(), user()));
        assertEquals(MemberUpdate.UPDATED, repository.changeRole(workspace.id(), member, WorkspaceRole.OWNER));
        assertEquals(MemberUpdate.UPDATED, repository.changeRole(workspace.id(), owner, WorkspaceRole.MEMBER));
        assertEquals(WorkspaceRole.MEMBER, repository.roleOf(workspace.id(), owner).orElseThrow());
    }

    @Test
    void 역할_변경은_워크스페이스_잠금을_잡고_확인한다() throws Exception {
        // 다른 연결이 같은 잠금을 쥐고 있는 동안 역할 변경은 기다려야 한다. 잠그지 않고 확인하면 두 OWNER 가 서로를 동시에
        // 내릴 때 둘 다 "OWNER 가 둘" 을 보고 통과해 OWNER 가 없어진다. 시간에 기대는 경쟁 시험 대신 잠금을 직접 확인한다.
        UUID second = user();
        repository.addMember(workspace.id(), second, WorkspaceRole.OWNER);
        try (Connection holder = dataSource.getConnection()) {
            try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, workspace.id().toString());
                lock.executeQuery();
            }
            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                Future<MemberUpdate> change =
                        pool.submit(() -> repository.changeRole(workspace.id(), second, WorkspaceRole.MEMBER));
                Thread.sleep(500);
                assertFalse(change.isDone(), "잠금을 기다리지 않았다");
                try (PreparedStatement unlock = holder.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                    unlock.setString(1, workspace.id().toString());
                    unlock.executeQuery();
                }
                assertEquals(MemberUpdate.UPDATED, change.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void 부모로_찾는_인덱스가_있다() {
        // 페이지를 지울 때의 외래 키 검사가 parent_id 만으로 찾는다. 없으면 테이블 전체를 훑는다(5만 행에서 지울 때마다 2.9ms).
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'pages' AND indexname = 'pages_parent'")
                .query(String.class)
                .single();
        assertTrue(definition.contains("(parent_id)"), definition);
    }

    @Test
    void 동시에_같은_자리에_만들어도_순서_키가_겹치지_않는다() throws Exception {
        Page anchor = create(null, null);
        List<Callable<Page>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID after = i % 2 == 0 ? null : anchor.id();
            tasks.add(() -> create(null, after));
        }
        List<Page> created = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Page> future : pool.invokeAll(tasks)) {
                created.add(future.get());
            }
        }

        assertEquals(21, new HashSet<>(jdbc.sql("SELECT pos FROM pages WHERE workspace_id = :id")
                .param("id", workspace.id())
                .query(String.class)
                .list()).size());
        assertEquals(21, children(null).size());
    }
}
