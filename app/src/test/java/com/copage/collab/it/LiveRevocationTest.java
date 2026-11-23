package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 권한을 바꾸면 열린 편집 연결에 1초 안에 적용된다 (docs/01-PLAN.md S-5). 내려가면 role 을 받고, 잃으면 4003 으로 닫힌다. 걸린
 * 시간을 API 응답을 받은 때부터 잰다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveRevocationTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";
    private static final long LIMIT_MS = 1_000;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Account(UUID id, String email, String token) {
    }

    private JsonNode send(String method, String path, Object json, Account who, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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
        assertEquals(expected, response.statusCode(), response.body());
        return response.body().isEmpty() ? null : MAPPER.readTree(response.body());
    }

    private Account account() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"), null, 201);
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null, 200);
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }

    private TestClient open(String pageId, Account who) throws Exception {
        String ticket = send("POST", "/api/docs/" + pageId + "/ws-ticket", null, who, 200).get("ticket").stringValue();
        TestClient client = new TestClient("tab-" + UUID.randomUUID());
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + pageId + "?ticket=" + ticket + "&v=1"));
        assertEquals("welcome", client.take().get("t").stringValue());
        return client;
    }

    /** role 메시지를 기다린다. 입장과 퇴장 같은 다른 메시지는 건너뛴다. */
    private static JsonNode awaitRole(TestClient client, long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            JsonNode message = client.take();
            if (message != null && "role".equals(message.get("t").stringValue())) {
                return message;
            }
        }
        throw new AssertionError("role 을 받지 못했다");
    }

    private static int awaitClose(TestClient client, long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            synchronized (client.closes) {
                if (!client.closes.isEmpty()) {
                    return client.closes.get(client.closes.size() - 1).code();
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("닫히지 않았다");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Test
    void 권한을_내리면_role_을_받고_회수하면_4003_으로_1초_안에_닫힌다() throws Exception {
        Account owner = account();
        Account guest = account();
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner, 201).get("id").stringValue();
        String page = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), owner, 201).get("id").stringValue();
        send("PUT", "/api/pages/" + page + "/grants/" + guest.id(), Map.of("level", "EDIT"), owner, 200);
        TestClient client = open(page, guest);

        long start = System.nanoTime();
        send("PUT", "/api/pages/" + page + "/grants/" + guest.id(), Map.of("level", "VIEW"), owner, 200);
        JsonNode role = awaitRole(client, start + LIMIT_MS * 1_000_000);
        assertEquals("VIEW", role.get("role").stringValue());
        long downgradeMs = elapsedMs(start);

        start = System.nanoTime();
        send("DELETE", "/api/pages/" + page + "/grants/" + guest.id(), null, owner, 204);
        assertEquals(4003, awaitClose(client, start + LIMIT_MS * 1_000_000));
        long revokeMs = elapsedMs(start);

        System.out.println("S-5 내리기 " + downgradeMs + "ms, 회수 " + revokeMs + "ms");
        assertTrue(downgradeMs < LIMIT_MS && revokeMs < LIMIT_MS);
    }

    @Test
    void 페이지를_제한하거나_멤버를_내보내도_1초_안에_닫힌다() throws Exception {
        Account owner = account();
        Account member = account();
        Account other = account();
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner, 201).get("id").stringValue();
        send("POST", "/api/workspaces/" + workspace + "/members", Map.of("email", member.email(), "role", "MEMBER"), owner, 201);
        send("POST", "/api/workspaces/" + workspace + "/members", Map.of("email", other.email(), "role", "MEMBER"), owner, 201);
        String root = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), owner, 201).get("id").stringValue();
        Map<String, Object> under = new java.util.HashMap<>();
        under.put("parentId", root);
        String child = send("POST", "/api/workspaces/" + workspace + "/pages", under, owner, 201).get("id").stringValue();
        TestClient memberOnChild = open(child, member);
        TestClient otherOnRoot = open(root, other);
        TestClient ownerOnChild = open(child, owner);

        // 부모를 제한하면 하위 페이지를 연 멤버도 기본 권한을 잃는다.
        long start = System.nanoTime();
        send("PUT", "/api/pages/" + root + "/restricted", Map.of("restricted", true), owner, 200);
        assertEquals(4003, awaitClose(memberOnChild, start + LIMIT_MS * 1_000_000));
        long restrictMs = elapsedMs(start);

        start = System.nanoTime();
        send("DELETE", "/api/workspaces/" + workspace + "/members/" + other.id(), null, owner, 204);
        assertEquals(4003, awaitClose(otherOnRoot, start + LIMIT_MS * 1_000_000));
        long removeMs = elapsedMs(start);

        System.out.println("S-5 제한 " + restrictMs + "ms, 내보내기 " + removeMs + "ms");
        // OWNER 는 영향이 없다.
        Thread.sleep(200);
        synchronized (ownerOnChild.closes) {
            assertTrue(ownerOnChild.closes.isEmpty());
        }
    }
}
