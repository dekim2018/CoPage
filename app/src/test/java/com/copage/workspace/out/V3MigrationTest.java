package com.copage.workspace.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.FractionalIndex;
import com.copage.support.TestInfra;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V3 가 이미 있는 문서를 지우지 않고 페이지로 옮기는지. 다른 테스트와 섞이지 않도록 새 스키마를 V2 까지 올리고 데이터를 넣은 뒤
 * V3 를 적용한다.
 */
class V3MigrationTest {

    private final String schema = "v3_" + UUID.randomUUID().toString().replace("-", "");
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl() + (TestInfra.POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                    + "currentSchema=" + schema,
            TestInfra.POSTGRES.getUsername(),
            TestInfra.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load();
    }

    private void user(UUID id) {
        jdbc.sql("INSERT INTO users (id, email, password_hash, name) VALUES (:id, :email, 'x', 'a')")
                .param("id", id)
                .param("email", id + "@example.com")
                .update();
    }

    private void op(UUID docId, long rev, UUID userId, String at) {
        jdbc.sql("""
                        INSERT INTO doc_ops (doc_id, rev, user_id, client_id, client_seq, ops, created_at)
                        VALUES (:docId, :rev, :userId, 'c', :rev, '[]', CAST(:at AS timestamptz))
                        """)
                .param("docId", docId)
                .param("rev", rev)
                .param("userId", userId)
                .param("at", at)
                .update();
    }

    @Test
    void 이미_있는_문서는_첫_작성자의_워크스페이스에_만든_순서대로_페이지가_된다() {
        flyway("2").migrate();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        user(alice);
        user(bob);
        UUID aliceLater = UUID.randomUUID();
        UUID aliceFirst = UUID.randomUUID();
        UUID bobDoc = UUID.randomUUID();
        UUID ghostDoc = UUID.randomUUID();
        op(aliceLater, 1, alice, "2026-01-02T00:00:00Z");
        // 나중 리비전을 다른 사람이 써도 주인은 첫 작성자다.
        op(aliceLater, 2, bob, "2026-01-03T00:00:00Z");
        op(aliceFirst, 1, alice, "2026-01-01T00:00:00Z");
        op(bobDoc, 1, bob, "2026-01-01T00:00:00Z");
        // 로그인 도입 전 개발 데이터처럼 users 에 없는 사용자다.
        op(ghostDoc, 1, ghost, "2026-01-01T00:00:00Z");

        flyway("latest").migrate();

        List<Map<String, Object>> pages = jdbc.sql("""
                        SELECT p.id, p.parent_id, p.pos, p.created_by, w.owner_id, w.name, m.role
                        FROM pages p
                        JOIN workspaces w ON w.id = p.workspace_id
                        JOIN workspace_members m ON m.workspace_id = w.id AND m.user_id = w.owner_id
                        ORDER BY p.created_by, p.pos
                        """)
                .query()
                .listOfRows();
        assertEquals(3, pages.size());
        List<UUID> alicePages = pages.stream()
                .filter(row -> alice.equals(row.get("created_by")))
                .map(row -> (UUID) row.get("id"))
                .toList();
        assertEquals(List.of(aliceFirst, aliceLater), alicePages);
        for (Map<String, Object> row : pages) {
            assertEquals(row.get("created_by"), row.get("owner_id"));
            assertEquals("OWNER", row.get("role"));
            assertEquals("가져온 문서", row.get("name"));
            assertEquals(null, row.get("parent_id"));
        }
        assertEquals(1, jdbc.sql("SELECT count(*) FROM workspaces WHERE owner_id = :id").param("id", alice)
                .query(Integer.class).single());
        // 옮기지 못한 문서도 연산 로그는 그대로다.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM pages WHERE id = :id").param("id", ghostDoc)
                .query(Integer.class).single());
        assertEquals(5, jdbc.sql("SELECT count(*) FROM doc_ops").query(Integer.class).single());
        // 마이그레이션이 만든 키 뒤에도 Java 의 분수 인덱스로 새 키를 만들 수 있다.
        List<String> alicePos = pages.stream()
                .filter(row -> alice.equals(row.get("created_by")))
                .map(row -> (String) row.get("pos"))
                .toList();
        String first = alicePos.get(0);
        String second = alicePos.get(1);
        assertTrue(first.compareTo(second) < 0);
        String between = FractionalIndex.between(first, second);
        assertTrue(first.compareTo(between) < 0 && between.compareTo(second) < 0);
        assertTrue(FractionalIndex.between(second, null).compareTo(second) > 0);
        // 임시 함수와 임시 테이블은 남지 않는다.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM pg_proc WHERE proname = 'copage_pos'")
                .query(Integer.class).single());
    }
}
