package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.LogCompactor;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 연산 로그 정리 (D-52). 오래된 로그를 고정 스냅샷으로 접어도 남긴 버전과 최신 문서는 그대로 열린다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogCompactionFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    LogCompactor compactor;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body) {
    }

    private Response send(String method, String path, Object json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(json)))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        return new Response(response.statusCode(), isJson ? MAPPER.readTree(response.body()) : null);
    }

    private String title(String page, long rev, String token) throws Exception {
        JsonNode blocks = send("GET", "/api/docs/" + page + "/versions/" + rev, null, token).body().get("document")
                .get("blocks");
        for (JsonNode block : blocks) {
            if (block.get("id").stringValue().equals(page)) {
                StringBuilder text = new StringBuilder();
                block.get("text").forEach(segment -> text.append(segment.get("insert").stringValue()));
                return text.toString();
            }
        }
        throw new AssertionError("제목 블록이 없다");
    }

    private void autoVersion(UUID docId, long rev, Instant at) {
        jdbc.sql("INSERT INTO doc_versions (id, doc_id, rev, kind, created_at) VALUES (:id, :docId, :rev, 'AUTO', :at)")
                .param("id", UUID.randomUUID()).param("docId", docId).param("rev", rev).param("at", Timestamp.from(at))
                .update();
    }

    @Test
    void 오래된_로그를_접어도_남긴_버전과_최신_문서는_열리고_나머지_시점은_410() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        send("POST", "/api/auth/signup", Map.of("email", email, "password", "correct horse 1", "name", "주인"), null);
        String token = send("POST", "/api/auth/login", Map.of("email", email, "password", "correct horse 1"), null).body()
                .get("accessToken").stringValue();
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), token).body().get("id").stringValue();
        String page = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), token).body().get("id")
                .stringValue();
        UUID docId = UUID.fromString(page);
        String ticket = send("POST", "/api/docs/" + page + "/ws-ticket", null, token).body().get("ticket").stringValue();
        TestClient client = new TestClient("writer");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
        while (!client.connected()) {
            client.process(5_000);
        }
        List<Long> revs = new ArrayList<>();
        for (String letter : List.of("a", "b", "c", "d", "e", "f")) {
            int length = client.session.view().get(page).text().length();
            client.edit(List.of(new TextOp(page, Delta.builder().retain(length).insert(letter).build())));
            while (client.session.hasPending()) {
                client.process(5_000);
            }
            revs.add(client.session.confirmedRev());
            if (letter.equals("b")) {
                assertEquals(201, send("POST", "/api/docs/" + page + "/versions",
                        Map.of("kind", "NAMED", "name", "초안"), token).status());
            }
        }
        long r2 = revs.get(1);
        long r3 = revs.get(2);
        long r4 = revs.get(3);
        long r5 = revs.get(4);
        long r6 = revs.get(5);
        // r1~r5 는 40일 전에 확정했다고 한다. 그날의 자동 시점이 둘(r3, r4)이면 마지막(r4)만 남는다.
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        jdbc.sql("UPDATE doc_ops SET created_at = :at WHERE doc_id = :docId AND rev <= :rev")
                .param("at", Timestamp.from(old)).param("docId", docId).param("rev", r5).update();
        autoVersion(docId, r3, old.truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS));
        autoVersion(docId, r4, old.truncatedTo(ChronoUnit.DAYS).plus(11, ChronoUnit.HOURS));

        assertTrue(compactor.compactOlderThan(Duration.ofDays(30), 100) >= 1);

        assertEquals(r5, jdbc.sql("SELECT through_rev FROM doc_compactions WHERE doc_id = :id").param("id", docId)
                .query(Long.class).single());
        assertEquals(r6, jdbc.sql("SELECT min(rev) FROM doc_ops WHERE doc_id = :id").param("id", docId)
                .query(Long.class).single(), "r5 까지의 로그는 지웠다");
        List<Long> versionRevs = new ArrayList<>();
        send("GET", "/api/docs/" + page + "/versions", null, token).body()
                .forEach(v -> versionRevs.add(v.get("rev").longValue()));
        assertTrue(versionRevs.contains(r2) && versionRevs.contains(r4), versionRevs.toString());
        assertTrue(!versionRevs.contains(r3), "같은 날의 앞선 자동 시점은 지웠다");

        assertEquals("ab", title(page, r2, token), "이름 붙인 시점");
        assertEquals("abcd", title(page, r4, token), "그날의 마지막 자동 시점");
        assertEquals("abcde", title(page, r5, token), "로그를 지운 지점");
        assertEquals("abcdef", title(page, r6, token));
        assertEquals(410, send("GET", "/api/docs/" + page + "/versions/" + r3, null, token).status());

        // 세션을 다시 올려도(새 연결) 최신 문서는 그대로다. 다시 정리해도 바뀌지 않는다.
        compactor.compactOlderThan(Duration.ofDays(30), 100);
        TestClient reader = new TestClient("reader");
        String readTicket = send("POST", "/api/docs/" + page + "/ws-ticket", null, token).body().get("ticket")
                .stringValue();
        reader.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + readTicket + "&v=1"));
        while (!reader.connected()) {
            reader.process(5_000);
        }
        assertEquals("abcdef", reader.session.view().get(page).text().plainText());
    }
}
