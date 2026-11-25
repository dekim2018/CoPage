package com.copage.collab.out;

import com.copage.collab.app.DocumentStore;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.FractionalIndex;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import com.copage.support.TestInfra;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷 간격 비교 (로드맵 6단계, D-51). 스냅샷 뒤에 쌓인 연산 수(K)에 따라 문서를 여는 시간과, 스냅샷 하나를 남기는 시간을
 * 실제 PostgreSQL 로 잰다. 세션을 정상으로 내리면 그때 스냅샷을 남기므로, 스냅샷 뒤 연산을 다시 적용하는 것은 서버가 갑자기
 * 죽은 뒤 처음 열 때다. 간격이 N 이면 그때 다시 적용하는 연산은 많아야 N 개다.
 *
 * <p>측정이라 평소 시험에서는 돌지 않는다: {@code ./gradlew :app:test --tests '*SnapshotIntervalBench' -Dcopage.bench=true}
 */
@EnabledIfSystemProperty(named = "copage.bench", matches = "true")
class SnapshotIntervalBench {

    private static final int BLOCKS = 2_000;
    private static final int[] TAILS = {0, 50, 200, 1_000, 5_000};
    private static final int RUNS = 7;

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            TestInfra.POSTGRES.getJdbcUrl(), TestInfra.POSTGRES.getUsername(), TestInfra.POSTGRES.getPassword());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final DocumentStore store = new JdbcDocumentStore(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), JsonMapper.builder().build(), 3);

    @Test
    void 스냅샷_뒤_연산_수에_따른_열기와_스냅샷_남기기() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID user = UUID.randomUUID();
        System.out.printf("블록 %d 개 문서. 스냅샷 뒤 연산은 한 글자 넣기(묶음 하나에 연산 하나, 실제 타이핑과 같은 모양)%n", BLOCKS);
        System.out.println("| 스냅샷 뒤 연산 | 열기 중앙값 | 열기 최대 |");
        for (int tail : TAILS) {
            UUID docId = UUID.randomUUID();
            Document document = filled(docId, user, now);
            store.saveSnapshot(docId, document, 1);
            Random random = new Random(tail);
            List<StoredChange> pending = new ArrayList<>();
            for (int i = 0; i < tail; i++) {
                String block = "b" + random.nextInt(BLOCKS);
                pending.add(new StoredChange(2 + i, user, "typist", 2 + i,
                        List.of(new TextOp(block, Delta.builder().insert("가").build())), now));
                if (pending.size() == 500) {
                    store.append(docId, pending);
                    pending = new ArrayList<>();
                }
            }
            if (!pending.isEmpty()) {
                store.append(docId, pending);
            }
            store.load(docId);
            store.load(docId);
            double[] times = new double[RUNS];
            for (int run = 0; run < RUNS; run++) {
                long start = System.nanoTime();
                store.load(docId);
                times[run] = (System.nanoTime() - start) / 1e6;
            }
            Arrays.sort(times);
            System.out.printf("| %d | %.1fms | %.1fms |%n", tail, times[RUNS / 2], times[RUNS - 1]);
        }

        UUID docId = UUID.randomUUID();
        Document document = filled(docId, user, now);
        double[] saves = new double[RUNS];
        for (int run = 0; run < RUNS; run++) {
            long start = System.nanoTime();
            store.saveSnapshot(docId, document, 1 + run);
            saves[run] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(saves);
        long bytes = jdbc.sql("SELECT max(pg_column_size(s.*)) FROM doc_snapshots s WHERE doc_id = :id")
                .param("id", docId).query(Long.class).single();
        System.out.printf("스냅샷 남기기 중앙값 %.1fms, 최대 %.1fms, 크기 %d 바이트%n", saves[RUNS / 2], saves[RUNS - 1], bytes);
    }

    /** 블록 BLOCKS 개를 리비전 1 하나로 넣은 문서. */
    private Document filled(UUID docId, UUID user, Instant now) {
        List<Op> ops = new ArrayList<>();
        String pos = null;
        for (int i = 0; i < BLOCKS; i++) {
            pos = FractionalIndex.between(pos, null);
            ops.add(new InsertBlock("b" + i, docId.toString(), pos, "paragraph", Attrs.NONE,
                    RichText.of("문단 " + i + " 에 들어가는 평범한 글 한 줄이다. 길이를 조금 갖춘다.")));
        }
        store.append(docId, List.of(new StoredChange(1, user, "filler", 1, ops, now)));
        return store.load(docId).document();
    }
}
