package com.copage.collab.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.DocumentStore.RevisionConflictException;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import com.copage.support.TestDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL 저장소를 실제 DB 에 대고 확인한다. */
class JdbcDocumentStoreTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestDatabase.POSTGRES.getJdbcUrl(), TestDatabase.POSTGRES.getUsername(), TestDatabase.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final DocumentStore store = new JdbcDocumentStore(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), JsonMapper.builder().build(), 3);
    private final UUID docId = UUID.randomUUID();
    private final String root = docId.toString();
    // DB 는 마이크로초까지만 저장하므로 비교할 시각도 맞춰 둔다.
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private StoredChange change(long rev, String clientId, long seq, Op... ops) {
        return new StoredChange(rev, USER, clientId, seq, List.of(ops), now);
    }

    private Op paragraph(String id, String text) {
        return new InsertBlock(id, root, "a" + id, "paragraph", Attrs.of("level", 2), RichText.of(text, Attrs.of("bold", true)));
    }

    @Test
    void 처음_여는_문서는_루트_블록만_있는_리비전_0_이다() {
        DocumentStore.Snapshot snapshot = store.load(docId);

        assertEquals(Document.create(root), snapshot.document());
        assertEquals(0, snapshot.rev());
        assertEquals(0, snapshot.snapshotRev());
    }

    @Test
    void 확정_묶음을_쓰고_읽으면_같은_묶음이고_문서는_로그를_적용한_결과다() {
        List<StoredChange> changes = List.of(
                change(1, "c1", 1, paragraph("X", "가나")),
                change(2, "c2", 1, new TextOp("X", Delta.builder().retain(2).insert("다").build()),
                        new SetProps("X", "checked", true), new DeleteBlock("X", false)));
        store.append(docId, changes);

        assertEquals(changes, store.changesSince(docId, 0));
        assertEquals(changes.subList(1, 2), store.changesSince(docId, 1));
        Document expected = Document.create(root).apply(changes.get(0).ops()).apply(changes.get(1).ops());
        assertEquals(new DocumentStore.Snapshot(expected, 2, 0), store.load(docId));
        assertEquals(OptionalLong.of(2), store.committedRev(docId, "c2", 1));
        assertEquals(OptionalLong.empty(), store.committedRev(docId, "c2", 2));
    }

    @Test
    void 이미_있는_리비전이나_순번이_섞인_묶음은_하나도_쓰지_않는다() {
        store.append(docId, List.of(change(1, "c1", 1, paragraph("X", "가"))));

        assertThrows(RevisionConflictException.class, () -> store.append(docId, List.of(
                change(2, "c1", 2, paragraph("Y", "나")),
                change(1, "c2", 1, paragraph("Z", "다")))));
        assertThrows(RevisionConflictException.class, () -> store.append(docId, List.of(
                change(2, "c1", 1, paragraph("Y", "나")))));

        assertEquals(1, store.changesSince(docId, 0).size());
    }

    @Test
    void 문서는_가장_최근_스냅샷에_그_뒤_연산을_적용해서_연다() {
        store.append(docId, List.of(change(1, "c1", 1, paragraph("X", "가")), change(2, "c1", 2, paragraph("Y", "나"))));
        Document atTwo = store.load(docId).document();
        store.saveSnapshot(docId, atTwo, 2);
        store.append(docId, List.of(change(3, "c1", 3, paragraph("Z", "다"))));

        DocumentStore.Snapshot loaded = store.load(docId);

        assertEquals(2, loaded.snapshotRev());
        assertEquals(3, loaded.rev());
        assertEquals(atTwo.apply(paragraph("Z", "다")), loaded.document());
    }

    @Test
    void 스냅샷은_최근_세_개만_남기고_같은_리비전을_다시_저장해도_그대로다() {
        Document doc = Document.create(root);
        for (long rev = 1; rev <= 5; rev++) {
            store.append(docId, List.of(change(rev, "c1", rev, paragraph("B" + rev, "가"))));
            doc = doc.apply(paragraph("B" + rev, "가"));
            store.saveSnapshot(docId, doc, rev);
        }
        store.saveSnapshot(docId, doc, 5);

        List<Long> revs = jdbc.sql("SELECT rev FROM doc_snapshots WHERE doc_id = :docId ORDER BY rev")
                .param("docId", docId).query(Long.class).list();
        assertEquals(List.of(3L, 4L, 5L), revs);
        assertTrue(store.load(docId).snapshotRev() == 5);
    }
}
