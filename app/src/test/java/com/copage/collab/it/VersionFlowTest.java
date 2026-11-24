package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.VersionLog;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 버전 기록 (docs/09-API.md 11.4, V-1, V-2, D-44) 을 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    @LocalServerPort
    int port;

    @Autowired
    VersionLog versionLog;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body) {
    }

    private record Account(UUID id, String email, String token) {
    }

    private Response send(String method, String path, Object json, Account who) throws Exception {
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
        return new Response(response.statusCode(), response.body().isEmpty() ? null : MAPPER.readTree(response.body()));
    }

    private Account account(String name) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", name), null)
                .body();
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null).body();
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }

    private TestClient open(String page, Account who) throws Exception {
        String ticket = send("POST", "/api/docs/" + page + "/ws-ticket", null, who).body().get("ticket").stringValue();
        TestClient client = new TestClient("tab-" + UUID.randomUUID());
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
        while (!client.connected()) {
            client.process(5_000);
        }
        return client;
    }

    private static void type(TestClient client, String block, String text) throws Exception {
        int length = client.session.view().get(block).text().length();
        client.edit(List.of(new TextOp(block, Delta.builder().retain(length).insert(text).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
    }

    @Test
    void 자동_버전은_편집한_사람을_적고_과거_시점의_문서를_열_수_있다() throws Exception {
        Account owner = account("주인");
        Account viewer = account("보는 사람");
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
        send("POST", "/api/workspaces/" + workspace + "/members", Map.of("email", viewer.email(), "role", "GUEST"), owner);
        String page = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), owner).body().get("id").stringValue();
        send("PUT", "/api/pages/" + page + "/grants/" + viewer.id(), Map.of("level", "VIEW"), owner);

        TestClient client = open(page, owner);
        type(client, page, "첫 판");
        long firstRev = client.session.confirmedRev();
        assertTrue(versionLog.recordAutoVersions(Duration.ZERO, Duration.ZERO) >= 1);
        type(client, page, " 둘째 판");
        versionLog.recordAutoVersions(Duration.ZERO, Duration.ZERO);

        JsonNode list = send("GET", "/api/docs/" + page + "/versions", null, viewer).body();
        assertEquals(2, list.size());
        assertEquals(client.session.confirmedRev(), list.get(0).get("rev").longValue(), "최근 버전이 먼저");
        assertEquals(firstRev, list.get(1).get("rev").longValue());
        assertEquals("AUTO", list.get(1).get("kind").stringValue());
        assertEquals("주인", list.get(1).get("editors").get(0).stringValue());
        assertEquals(1, send("GET", "/api/docs/" + page + "/versions?before=" + list.get(0).get("rev").longValue(), null,
                viewer).body().size(), "이전 쪽으로 넘기기");

        JsonNode past = send("GET", "/api/docs/" + page + "/versions/" + firstRev, null, viewer).body();
        String rootText = past.get("document").get("blocks").toString();
        assertTrue(rootText.contains("첫 판") && !rootText.contains("둘째"), rootText);

        // 이름 붙이기는 EDIT 부터. 같은 시점에 다시 이름을 붙이면 이름을 바꾼다.
        assertEquals(403, send("POST", "/api/docs/" + page + "/versions", Map.of("kind", "NAMED", "name", "초안"), viewer)
                .status());
        assertEquals(201, send("POST", "/api/docs/" + page + "/versions", Map.of("kind", "NAMED", "name", "초안"), owner)
                .status());
        JsonNode restored = send("POST", "/api/docs/" + page + "/versions",
                Map.of("kind", "RESTORE", "restoredFrom", firstRev), owner).body();
        assertEquals("리비전 " + firstRev + " 으로 복원", restored.get("name").stringValue());
        assertEquals(400, send("POST", "/api/docs/" + page + "/versions", Map.of("kind", "AUTO"), owner).status());
        assertEquals(404, send("GET", "/api/docs/" + page + "/versions", null, account("남")).status());
    }
}
