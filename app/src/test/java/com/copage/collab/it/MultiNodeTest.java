package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.CoPageApplication;
import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.TicketService;
import com.copage.collab.domain.PageRole;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노드 두 대 (docs/03-ARCHITECTURE.md 5.3~5.5, D-43). 한 JVM 에 서버를 둘 띄워 같은 PostgreSQL 과 Redis 를 쓰게 한다. 프록시는
 * 없고, 시험이 어느 노드에 붙을지 고른다. 리스 시간은 줄였다(만료 2초, 이어 쥐기 0.3초, 넘기기 유예 0.5초).
 */
class MultiNodeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    private ConfigurableApplicationContext nodeA;
    private ConfigurableApplicationContext nodeB;
    private final HttpClient http = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext start(String name) {
        Map<String, Object> properties = new HashMap<>(TestInfra.properties());
        properties.put("server.port", "0");
        properties.put("management.server.port", "-1");
        properties.put("copage.web.node-id", name);
        properties.put("copage.collab.lease-ttl", "2s");
        properties.put("copage.collab.lease-renew-interval", "300ms");
        properties.put("copage.collab.handoff-grace", "500ms");
        // properties() 는 기본값이라 application.yml 이 덮는다. 명령줄 인자로 넘긴다.
        String[] args = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(CoPageApplication.class).run(args);
    }

    @BeforeEach
    void startNodes() {
        nodeA = start("node-a-" + UUID.randomUUID());
        nodeB = start("node-b-" + UUID.randomUUID());
    }

    @AfterEach
    void stopNodes() {
        if (nodeA != null && nodeA.isActive()) {
            nodeA.close();
        }
        if (nodeB != null && nodeB.isActive()) {
            nodeB.close();
        }
    }

    private static int port(ConfigurableApplicationContext node) {
        return Integer.parseInt(node.getEnvironment().getProperty("local.server.port", "0"));
    }

    private URI uri(ConfigurableApplicationContext node, UUID docId) {
        String ticket = nodeB.getBean(TicketService.class).issue(UUID.randomUUID(), docId, PageRole.EDIT, "편집자");
        return URI.create("ws://localhost:" + port(node) + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=1");
    }

    /** welcome 을 받을 때까지 그 노드에 다시 붙는다. 다른 노드가 맡고 있으면 4009 로 닫히고, 넘겨받을 때까지 되풀이한다. */
    private void joinUntilWelcome(TestClient client, ConfigurableApplicationContext node, UUID docId, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int closesBefore = client.closes.size();
            client.connect(uri(node, docId));
            long attemptEnd = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < attemptEnd && !client.connected() && client.closes.size() == closesBefore) {
                client.process(50);
            }
            if (client.connected()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(client.clientId + " 가 " + timeoutMs + "ms 안에 붙지 못했다");
    }

    private static void type(TestClient client, String blockId, String text) throws Exception {
        int length = client.session.view().get(blockId).text().length();
        client.edit(List.of(new TextOp(blockId, Delta.builder().retain(length).insert(text).build())));
    }

    private static void settle(TestClient... clients) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean done = true;
            for (TestClient client : clients) {
                client.process(20);
                done &= client.connected() && !client.session.hasPending();
            }
            long rev = clients[0].session.confirmedRev();
            for (TestClient client : clients) {
                done &= client.session.confirmedRev() == rev;
            }
            if (done) {
                return;
            }
        }
        throw new AssertionError("수렴하지 않았다");
    }

    private static int awaitClose(TestClient client, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            client.process(20);
            if (client.lastClose().isPresent() && !client.connected()) {
                return client.lastClose().get().code();
            }
        }
        throw new AssertionError("닫히지 않았다");
    }

    private String owner(UUID docId) {
        return nodeB.getBean(StringRedisTemplate.class).opsForValue().get("copage:doc:" + docId + ":owner");
    }

    private void assertLogIsClean(UUID docId) {
        JdbcClient jdbc = nodeB.getBean(JdbcClient.class);
        long rows = jdbc.sql("SELECT count(*) FROM doc_ops WHERE doc_id = :id").param("id", docId).query(Long.class).single();
        long maxRev = jdbc.sql("SELECT coalesce(max(rev), 0) FROM doc_ops WHERE doc_id = :id")
                .param("id", docId).query(Long.class).single();
        assertEquals(maxRev, rows, "리비전에 빈틈이 없고 겹치지 않는다");
    }

    @Test
    void 한_문서는_한_노드가_맡고_다른_노드로_온_연결은_넘겨받은_뒤_이어진다() throws Exception {
        UUID docId = UUID.randomUUID();
        String root = docId.toString();
        TestClient first = new TestClient("first");
        joinUntilWelcome(first, nodeA, docId, 5_000);
        type(first, root, "가");
        settle(first);
        String nodeAName = nodeA.getEnvironment().getProperty("copage.web.node-id");
        assertEquals(nodeAName, owner(docId));

        // B 에 온 연결은 A 가 넘겨줄 때까지 4009 로 되돌아간다. 넘기면 A 의 연결도 4009 로 닫히고 B 로 다시 붙는다.
        TestClient second = new TestClient("second");
        joinUntilWelcome(second, nodeB, docId, 10_000);
        assertEquals(CloseCodes.MOVED, awaitClose(first, 3_000));
        joinUntilWelcome(first, nodeB, docId, 5_000);
        assertEquals(nodeB.getEnvironment().getProperty("copage.web.node-id"), owner(docId));

        type(first, root, "나");
        type(second, root, "다");
        settle(first, second);
        assertEquals(first.session.view(), second.session.view());
        String text = first.session.view().get(root).text().plainText();
        assertEquals(3, text.length());
        assertTrue(text.startsWith("가") && text.contains("나") && text.contains("다"), text);
        assertLogIsClean(docId);
    }

    @Test
    void 리스를_잃은_노드는_세션을_버리고_같은_리비전이_두_번_들어가지_않는다() throws Exception {
        UUID docId = UUID.randomUUID();
        String root = docId.toString();
        TestClient onA = new TestClient("on-a");
        joinUntilWelcome(onA, nodeA, docId, 5_000);
        type(onA, root, "가");
        settle(onA);

        // A 가 멈춰 리스가 만료된 것처럼 만든다. B 가 바로 잡고 세션을 연다. A 는 아직 자기가 주인인 줄 안다.
        nodeB.getBean(StringRedisTemplate.class).delete("copage:doc:" + docId + ":owner");
        TestClient onB = new TestClient("on-b");
        joinUntilWelcome(onB, nodeB, docId, 5_000);
        type(onA, root, "나");
        type(onB, root, "다");

        // 두 노드가 같은 리비전을 쓰려 하면 늦은 쪽의 커밋이 기본 키에 걸려 그 세션을 버린다. A 는 이어 쥐기에서 리스를 잃은
        // 것을 알고도 버린다. 어느 쪽이 먼저든 A 의 연결은 끝내 4009 로 닫힌다. 닫힌 클라이언트는 B 로 다시 붙는다.
        assertEquals(CloseCodes.MOVED, awaitClose(onA, 3_000));
        joinUntilWelcome(onA, nodeB, docId, 5_000);
        if (!onB.connected()) {
            joinUntilWelcome(onB, nodeB, docId, 5_000);
        }
        settle(onA, onB);

        String text = onA.session.view().get(root).text().plainText();
        assertEquals(onA.session.view(), onB.session.view());
        assertEquals(1, text.chars().filter(c -> c == '나').count(), text);
        assertEquals(1, text.chars().filter(c -> c == '다').count(), text);
        assertLogIsClean(docId);
    }

    @Test
    void 노드를_계획해서_내리면_세션을_넘기고_리스를_바로_푼다() throws Exception {
        UUID docId = UUID.randomUUID();
        String root = docId.toString();
        TestClient client = new TestClient("client");
        joinUntilWelcome(client, nodeA, docId, 5_000);
        type(client, root, "가");
        settle(client);

        long stoppedAt = System.currentTimeMillis();
        nodeA.close();
        assertEquals(CloseCodes.MOVED, awaitClose(client, 3_000));
        assertEquals(null, owner(docId), "리스 만료(2초)를 기다리지 않고 풀었다");
        joinUntilWelcome(client, nodeB, docId, 5_000);
        long resumedAfter = System.currentTimeMillis() - stoppedAt;
        type(client, root, "나");
        settle(client);

        assertEquals("가나", client.session.view().get(root).text().plainText());
        assertLogIsClean(docId);
        System.out.printf("계획 종료 뒤 다른 노드에서 %dms 에 이어 붙었다%n", resumedAfter);
    }

    @Test
    void 다른_노드에서_바꾼_권한도_1초_안에_열린_연결에_적용된다() throws Exception {
        Account owner = account(nodeB);
        Account member = account(nodeB);
        String workspace = send(nodeB, "POST", "/api/workspaces", Map.of("name", "팀"), owner).get("id").stringValue();
        send(nodeB, "POST", "/api/workspaces/" + workspace + "/members",
                Map.of("email", member.email(), "role", "MEMBER"), owner);
        String page = send(nodeB, "POST", "/api/workspaces/" + workspace + "/pages", Map.of(), owner).get("id").stringValue();

        // 멤버는 A 에 붙는다(A 가 문서를 맡는다). 권한은 B 의 API 로 바꾼다.
        String ticket = send(nodeA, "POST", "/api/docs/" + page + "/ws-ticket", null, member).get("ticket").stringValue();
        TestClient client = new TestClient("member");
        client.connect(URI.create("ws://localhost:" + port(nodeA) + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
        long deadline = System.currentTimeMillis() + 5_000;
        while (!client.connected() && System.currentTimeMillis() < deadline) {
            client.process(50);
        }
        assertTrue(client.connected());

        long changedAt = System.nanoTime();
        send(nodeB, "PUT", "/api/pages/" + page + "/restricted", Map.of("restricted", true), owner);
        assertEquals(CloseCodes.FORBIDDEN, awaitClose(client, 1_000));
        System.out.printf("다른 노드의 권한 변경이 %dms 에 적용되었다%n", (System.nanoTime() - changedAt) / 1_000_000);
        assertFalse(client.connected());
    }

    private record Account(UUID id, String email, String token) {
    }

    private JsonNode send(ConfigurableApplicationContext node, String method, String path, Object json, Account who)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port(node) + path))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(json)));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, method + " " + path + " " + response.statusCode() + " " + response.body());
        return response.body().isEmpty() ? null : MAPPER.readTree(response.body());
    }

    private Account account(ConfigurableApplicationContext node) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send(node, "POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"),
                null);
        JsonNode login = send(node, "POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null);
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }
}
