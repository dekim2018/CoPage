package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.TicketService;
import com.copage.collab.domain.PageRole;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * PostgreSQL 의 응답이 느릴 때 (docs/11-TEST-PLAN.md 13.2, docs/CHAOS.md). 모든 DB 응답에 200ms 를 더해도 묶음 커밋이
 * 처리량을 지키고, 남에게 보이는 묶음은 언제나 이미 저장된 것이며, 모두 같은 문서가 된다.
 *
 * <p>앱은 Toxiproxy 를 거쳐 이 시험만의 PostgreSQL 에 붙는다. 저장 여부는 Toxiproxy 를 거치지 않는 연결로 바로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DbLatencyTest {

    private static final int WRITERS = 4;
    private static final int EDITS_EACH = 15;
    private static final long LATENCY_MS = 200;

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer POSTGRES = start(
            new PostgreSQLContainer("postgres:17").withNetwork(NETWORK).withNetworkAliases("pg"));
    private static final ToxiproxyContainer TOXIPROXY = start(
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(NETWORK));
    private static final Proxy PROXY = proxy();

    private static <T extends org.testcontainers.containers.GenericContainer<?>> T start(T container) {
        container.start();
        return container;
    }

    private static Proxy proxy() {
        try {
            return new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("pg", "0.0.0.0:8666", "pg:5432");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestInfra.properties().forEach((key, value) -> registry.add(key, () -> value));
        String proxied = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666) + "/"
                + POSTGRES.getDatabaseName();
        registry.add("spring.datasource.url", () -> proxied);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TicketService tickets;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void removeLatency() throws Exception {
        for (var toxic : PROXY.toxics().getAll()) {
            toxic.remove();
        }
    }

    private URI uri(UUID docId) {
        String ticket = tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT, "테스터");
        return URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=1");
    }

    @Test
    void DB_응답이_느려도_묶음_커밋으로_처리량을_지키고_저장되지_않은_묶음은_남에게_보이지_않는다() throws Exception {
        UUID docId = UUID.randomUUID();
        List<TestClient> writers = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            TestClient writer = new TestClient("w" + i);
            writer.connect(uri(docId));
            writer.process(5_000);
            writers.add(writer);
        }
        RawClient observer = new RawClient(false);
        observer.connect(uri(docId), "observer");
        long commitsBefore = meters.get("collab.commit.batch.size").summary().count();

        PROXY.toxics().latency("latency", ToxicDirection.DOWNSTREAM, LATENCY_MS);
        long startedAt = System.currentTimeMillis();
        int[] sent = new int[WRITERS];
        int remotesSeen = 0;
        try (Connection direct = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            while (true) {
                boolean done = true;
                for (int i = 0; i < WRITERS; i++) {
                    TestClient writer = writers.get(i);
                    // 답을 기다리는 동안에는 보내지 않는다. 그래서 커밋 한 번이 묶을 수 있는 것은 사람마다 하나다.
                    if (sent[i] < EDITS_EACH && !writer.session.hasPending()) {
                        int length = writer.session.view().get(docId.toString()).text().length();
                        writer.edit(List.of(new TextOp(docId.toString(),
                                Delta.builder().retain(length).insert(String.valueOf((char) ('a' + i))).build())));
                        sent[i]++;
                    }
                    writer.process(5);
                    done &= sent[i] == EDITS_EACH && !writer.session.hasPending();
                }
                // 남에게 보인 묶음은 이미 저장되어 있다.
                for (RawClient.Received r; (r = observer.inbox.poll()) != null; ) {
                    if ("remote".equals(r.message().get("t").stringValue())) {
                        long rev = r.message().get("rev").longValue();
                        assertTrue(stored(direct, docId, rev), "저장되지 않은 리비전 " + rev + " 이 남에게 보였다");
                        remotesSeen++;
                    }
                }
                // 내 묶음이 다 확정된 뒤에도 남의 마지막 묶음이 아직 오는 중일 수 있다. 모두 마지막 리비전에 이르면 끝낸다.
                if (done && remotesSeen == WRITERS * EDITS_EACH
                        && writers.stream().allMatch(w -> w.session.confirmedRev() == writers.get(0).session.confirmedRev())) {
                    break;
                }
                assertTrue(System.currentTimeMillis() - startedAt < 60_000, "60초 안에 끝나지 않았다");
            }
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        String text = writers.get(0).session.view().get(docId.toString()).text().plainText();
        assertEquals(WRITERS * EDITS_EACH, text.length());
        for (TestClient writer : writers) {
            assertEquals(writers.get(0).session.view(), writer.session.view());
        }
        assertEquals(WRITERS * EDITS_EACH, remotesSeen);
        long commits = meters.get("collab.commit.batch.size").summary().count() - commitsBefore;
        // 묶지 않으면 묶음마다 커밋한다(60번). 커밋 하나가 DB 를 여러 번 왕복하므로, 묶으면 앞 커밋을 기다리는 동안 모인 묶음이
        // 함께 확정되어 커밋 수가 줄어든다.
        assertTrue(commits < WRITERS * EDITS_EACH, "묶음 커밋이 동작하지 않았다: 커밋 " + commits + " 번");
        System.out.printf("DB 지연 %dms 실험: 묶음 %d 개를 %dms 에 확정(초당 %.1f), 커밋 %d 번(평균 묶음 %.1f)%n", LATENCY_MS,
                WRITERS * EDITS_EACH, elapsed, WRITERS * EDITS_EACH * 1000.0 / elapsed, commits,
                (double) WRITERS * EDITS_EACH / commits);
    }

    private static boolean stored(Connection direct, UUID docId, long rev) throws Exception {
        try (PreparedStatement statement = direct.prepareStatement("SELECT 1 FROM doc_ops WHERE doc_id = ? AND rev = ?")) {
            statement.setObject(1, docId);
            statement.setLong(2, rev);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
