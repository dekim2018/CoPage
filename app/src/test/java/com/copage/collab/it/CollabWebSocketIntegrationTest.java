package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.TicketService;
import com.copage.collab.domain.PageRole;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버를 띄우고 실제 WebSocket 으로 붙어 규약과 수렴을 확인한다. 클라이언트의 상태 기계는 ot-core 의 ClientSession 이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollabWebSocketIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    TicketService tickets;

    @Autowired
    DocumentStore store;

    private URI uri(UUID docId, String ticket, String version) {
        return URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=" + version);
    }

    private URI uri(UUID docId) {
        return uri(docId, tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT), "1");
    }

    private static int awaitClose(TestClient client) throws InterruptedException {
        for (int i = 0; i < 100 && client.lastClose().isEmpty(); i++) {
            Thread.sleep(50);
        }
        return client.lastClose().orElseThrow(() -> new AssertionError("연결이 닫히지 않았다")).code();
    }

    @Test
    void 여러_클라이언트가_동시에_편집하고_끊겼다_붙어도_모두_서버와_같은_문서가_된다() throws Exception {
        for (long seed = 1; seed <= 3; seed++) {
            converge(seed);
        }
    }

    private void converge(long seed) throws Exception {
        Random random = new Random(seed);
        UUID docId = UUID.randomUUID();
        List<TestClient> clients = new ArrayList<>();
        List<RandomEdits> editors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestClient client = new TestClient("s" + seed + "c" + i);
            client.connect(uri(docId));
            clients.add(client);
            editors.add(new RandomEdits(random, client.clientId));
        }
        for (TestClient client : clients) {
            while (client.session == null) {
                client.process(5_000);
            }
        }

        for (int step = 0; step < 150; step++) {
            int who = random.nextInt(clients.size());
            TestClient client = clients.get(who);
            int roll = random.nextInt(100);
            if (roll < 70 && client.connected()) {
                Op op = editors.get(who).next(client.session.view());
                client.edit(List.of(op));
            } else if (roll < 80 && client.connected()) {
                client.disconnect();
            } else if (!client.connected() && client.lastClose().isPresent()) {
                client.connect(uri(docId));
            }
            for (TestClient c : clients) {
                c.process(random.nextInt(3));
            }
        }

        for (TestClient client : clients) {
            if (!client.connected()) {
                client.connect(uri(docId));
            }
        }
        long deadline = System.currentTimeMillis() + 20_000;
        while (!settled(docId, clients)) {
            assertTrue(System.currentTimeMillis() < deadline, "시드 " + seed + ": 20초 안에 수렴하지 않았다");
            for (TestClient client : clients) {
                client.process(20);
            }
        }

        DocumentStore.Snapshot server = store.load(docId);
        int reconnects = clients.stream().mapToInt(c -> c.closes.size()).sum();
        int nacks = clients.stream().mapToInt(c -> c.nacks).sum();
        System.out.println("시드 " + seed + ": 확정 " + server.rev() + ", 재접속 " + reconnects + ", nack " + nacks);
        assertTrue(server.rev() > 20, "시드 " + seed + ": 확정된 묶음이 너무 적다 " + server.rev());
        assertTrue(reconnects > 0, "시드 " + seed + ": 끊김과 재접속을 한 번도 지나지 않았다");
        for (TestClient client : clients) {
            assertEquals(server.document(), client.session.confirmed(), "시드 " + seed + " " + client.clientId);
            assertEquals(server.document(), client.session.view(), "시드 " + seed + " " + client.clientId);
        }
    }

    private boolean settled(UUID docId, List<TestClient> clients) {
        long rev = store.load(docId).rev();
        for (TestClient client : clients) {
            if (!client.connected() || client.session.hasPending() || client.session.confirmedRev() != rev) {
                return false;
            }
        }
        return true;
    }

    @Test
    void 티켓이_없거나_이미_쓴_티켓이면_4001_로_끊는다() throws Exception {
        UUID docId = UUID.randomUUID();
        TestClient noTicket = new TestClient("a");
        noTicket.connect(uri(docId, "nope", "1"));
        assertEquals(CloseCodes.TICKET, awaitClose(noTicket));

        String ticket = tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT);
        TestClient first = new TestClient("b");
        first.connect(uri(docId, ticket, "1"));
        assertNotNull(first.take());
        TestClient reused = new TestClient("c");
        reused.connect(uri(docId, ticket, "1"));
        assertEquals(CloseCodes.TICKET, awaitClose(reused));

        // 다른 문서의 티켓으로는 열 수 없다.
        TestClient otherDoc = new TestClient("d");
        otherDoc.connect(uri(UUID.randomUUID(), tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT), "1"));
        assertEquals(CloseCodes.TICKET, awaitClose(otherDoc));
    }

    @Test
    void 규약_버전이_다르면_4010_으로_끊는다() throws Exception {
        UUID docId = UUID.randomUUID();
        TestClient client = new TestClient("a");
        client.connect(uri(docId, tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT), "2"));

        assertEquals(CloseCodes.VERSION, awaitClose(client));
    }

    @Test
    void hello_보다_change_가_먼저_오거나_한도를_넘는_메시지는_4008_로_끊는다() throws Exception {
        UUID docId = UUID.randomUUID();
        TestClient early = new TestClient("a");
        early.connect(uri(docId));
        early.take();
        early.send(MAPPER.readTree("{\"t\":\"hello\",\"clientId\":\"a\",\"lastRev\":0}"));
        assertEquals(CloseCodes.PROTOCOL, awaitClose(early), "hello 를 두 번 보냈다");

        TestClient big = new TestClient("b");
        big.connect(uri(docId));
        big.take();
        String text = "가".repeat(30_000);
        JsonNode change = MAPPER.readTree("{\"t\":\"change\",\"clientSeq\":1,\"baseRev\":0,\"ops\":[{\"op\":\"text\",\"blockId\":\""
                + docId + "\",\"delta\":[{\"insert\":\"" + text + "\"}]}]}");
        big.send(change);
        assertEquals(CloseCodes.PROTOCOL, awaitClose(big), "64KB 를 넘었다");
    }

    @Test
    void 읽기_권한으로_보낸_편집은_nack_를_받는다() throws Exception {
        UUID docId = UUID.randomUUID();
        TestClient viewer = new TestClient("v");
        viewer.connect(uri(docId, tickets.issue(UUID.randomUUID(), docId, PageRole.VIEW), "1"));
        JsonNode welcome = viewer.take();
        assertEquals("VIEW", welcome.get("role").stringValue());

        viewer.send(MAPPER.readTree("{\"t\":\"change\",\"clientSeq\":1,\"baseRev\":0,\"ops\":[{\"op\":\"text\",\"blockId\":\""
                + docId + "\",\"delta\":[{\"insert\":\"제목\"}]}]}"));
        JsonNode nack = viewer.take();

        assertEquals("nack", nack.get("t").stringValue());
        assertEquals(0, store.load(docId).rev());
        assertFalse(viewer.lastClose().isPresent());
        Document unchanged = store.load(docId).document();
        assertEquals(0, unchanged.get(docId.toString()).text().length());
    }
}
