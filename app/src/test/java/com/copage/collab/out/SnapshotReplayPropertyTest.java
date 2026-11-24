package com.copage.collab.out;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.support.Iterations;
import com.copage.ot.support.RandomOps;
import com.copage.support.TestInfra;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷과 그 뒤 로그로 연 문서가, 로그를 처음부터 다시 적용한 문서와 같은지 무작위로 확인한다 (진단 DC-7). 연산은 이모지와 긴
 * 글을 섞어 만들고 실제 PostgreSQL 의 jsonb 를 거친다. 스냅샷은 무작위 리비전에 남기고, 남기는 개수 한도(3)로 옛 스냅샷이
 * 지워지는 경우도 지난다.
 */
class SnapshotReplayPropertyTest {

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl(), TestInfra.POSTGRES.getUsername(), TestInfra.POSTGRES.getPassword());
    private final DocumentStore store = new JdbcDocumentStore(
            JdbcClient.create(dataSource),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            JsonMapper.builder().build(),
            3);

    @Test
    void 스냅샷으로_연_문서와_로그를_처음부터_다시_적용한_문서가_같다() {
        long seed = 20261124L;
        Random random = new Random(seed);
        int trials = Iterations.of(15);
        for (int trial = 0; trial < trials; trial++) {
            UUID docId = UUID.randomUUID();
            RandomOps ops = new RandomOps(random, "t" + trial + "-");
            Document empty = store.load(docId).document();
            Document current = empty;
            int revisions = 20 + random.nextInt(100);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            for (int rev = 1; rev <= revisions; rev++) {
                List<Op> change = ops.randomChange(current, 4);
                current = current.apply(change);
                store.append(docId, List.of(new StoredChange(rev, UUID.randomUUID(), "c", rev, change, now)));
                if (random.nextInt(10) == 0) {
                    store.saveSnapshot(docId, current, rev);
                }
            }

            DocumentStore.Snapshot loaded = store.load(docId);
            Document replayed = empty;
            for (StoredChange stored : store.changesSince(docId, 0)) {
                replayed = replayed.apply(stored.ops());
            }

            String where = "시드 " + seed + ", 시도 " + trial + ", 리비전 " + revisions;
            assertEquals(revisions, loaded.rev(), where);
            assertEquals(current, loaded.document(), where + ": 스냅샷으로 연 문서");
            assertEquals(current, replayed, where + ": 처음부터 다시 적용한 문서");
        }
    }
}
