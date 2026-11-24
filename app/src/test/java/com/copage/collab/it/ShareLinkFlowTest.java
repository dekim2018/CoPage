package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.CloseCodes;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 페이지 공유 링크 (docs/09-API.md 11.3, D-41) 를 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShareLinkFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

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
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        return new Response(response.statusCode(),
                isJson && !response.body().isEmpty() ? MAPPER.readTree(response.body()) : null);
    }

    private Account account() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"), null)
                .body();
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null).body();
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }

    private final class Team {
        final Account owner = account();
        final Account member = account();
        final String id;

        Team() throws Exception {
            id = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
            send("POST", "/api/workspaces/" + id + "/members", Map.of("email", member.email(), "role", "MEMBER"), owner);
        }

        String page(String parentId) throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("parentId", parentId);
            return send("POST", "/api/workspaces/" + id + "/pages", body, owner).body().get("id").stringValue();
        }
    }

    private Response share(String pageId, String level, Instant expiresAt, boolean regenerate, Account by)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("level", level);
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        body.put("regenerate", regenerate);
        return send("PUT", "/api/pages/" + pageId + "/share-link", body, by);
    }

    private Response ticket(String token, Account who) throws Exception {
        return send("POST", "/api/share/" + token + "/ws-ticket", null, who);
    }

    private RawClient connect(String token) throws Exception {
        JsonNode issued = ticket(token, null).body();
        RawClient client = new RawClient(false);
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + issued.get("pageId").stringValue()
                + "?ticket=" + issued.get("ticket").stringValue() + "&v=1"), "visitor-" + UUID.randomUUID());
        return client;
    }

    @Test
    void 공유_설정은_FULL_만_바꾸고_링크로는_전체_권한을_줄_수_없다() throws Exception {
        Team team = new Team();
        String page = team.page(null);

        assertEquals(403, share(page, "VIEW", null, false, team.member).status(), "MEMBER 의 기본 권한은 EDIT 다");
        assertEquals(404, share(page, "VIEW", null, false, account()).status());
        assertEquals(400, share(page, "FULL", null, false, team.owner).status());
        assertEquals(400, share(page, "VIEW", Instant.now().minusSeconds(60), false, team.owner).status());
        assertEquals(204, send("GET", "/api/pages/" + page + "/share-link", null, team.owner).status(), "아직 없다");
    }

    @Test
    void 링크를_가진_사람은_로그인하지_않아도_링크의_등급으로_연다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        Response saved = share(page, "VIEW", null, false, team.owner);
        assertEquals(200, saved.status());
        String token = saved.body().get("token").stringValue();
        // 다시 읽으면 토큰은 없다. 저장소에도 해시만 있다.
        JsonNode read = send("GET", "/api/pages/" + page + "/share-link", null, team.owner).body();
        assertEquals("VIEW", read.get("level").stringValue());
        assertTrue(read.get("token").isNull());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM share_links WHERE token_hash = :token")
                .param("token", token).query(Long.class).single());

        Response opened = send("GET", "/api/share/" + token, null, null);
        assertEquals(200, opened.status());
        assertEquals(page, opened.body().get("pageId").stringValue());
        assertEquals("VIEW", opened.body().get("level").stringValue());
        JsonNode anonymous = ticket(token, null).body();
        assertEquals("VIEW", anonymous.get("role").stringValue());
        // 로그인한 멤버는 자기 등급(EDIT)과 링크 등급 중 높은 것을 받는다.
        assertEquals("EDIT", ticket(token, team.member).body().get("role").stringValue());
        // 링크는 그 페이지에만 적용된다. 링크로는 하위 페이지의 티켓을 받는 경로가 없다.
        String child = team.page(page);
        assertEquals(404, send("POST", "/api/docs/" + child + "/ws-ticket", null, account()).status());
        assertEquals(404, send("GET", "/api/share/not-a-token", null, null).status());
    }

    @Test
    void 새로_만들거나_끄거나_만료되거나_휴지통에_가면_링크로_열_수_없다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        String first = share(page, "VIEW", null, false, team.owner).body().get("token").stringValue();

        // 등급만 바꾸면 토큰은 그대로다.
        Response changed = share(page, "EDIT", null, false, team.owner);
        assertTrue(changed.body().get("token").isNull());
        assertEquals("EDIT", send("GET", "/api/share/" + first, null, null).body().get("level").stringValue());

        String second = share(page, "VIEW", null, true, team.owner).body().get("token").stringValue();
        assertEquals(404, send("GET", "/api/share/" + first, null, null).status(), "새로 만들면 예전 링크는 끝난다");
        assertEquals(200, send("GET", "/api/share/" + second, null, null).status());

        assertEquals(204, send("DELETE", "/api/pages/" + page + "/share-link", null, team.owner).status());
        assertEquals(404, send("GET", "/api/share/" + second, null, null).status());
        assertEquals(404, ticket(second, null).status());

        String expired = "expired-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO share_links (id, page_id, token_hash, level, expires_at, created_by)
                        VALUES (:id, :pageId, :hash, 'VIEW', :expiresAt, :owner)
                        """)
                .param("id", UUID.randomUUID())
                .param("pageId", UUID.fromString(page))
                .param("hash", sha256(expired))
                .param("expiresAt", Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .param("owner", team.owner.id())
                .update();
        assertEquals(404, send("GET", "/api/share/" + expired, null, null).status());

        String live = share(page, "VIEW", null, true, team.owner).body().get("token").stringValue();
        send("DELETE", "/api/pages/" + page, null, team.owner);
        assertEquals(404, send("GET", "/api/share/" + live, null, null).status(), "휴지통의 페이지");
    }

    @Test
    void 링크로_연_연결은_등급이_바뀌면_role_을_받고_링크를_끄면_닫힌다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        String token = share(page, "VIEW", null, false, team.owner).body().get("token").stringValue();
        RawClient visitor = connect(token);

        share(page, "EDIT", null, false, team.owner);
        RawClient.Received role = visitor.await("role", 1_000);
        assertEquals("EDIT", role.message().get("role").stringValue());
        assertFalse(visitor.closed.isDone());

        long disabledAt = System.nanoTime();
        send("DELETE", "/api/pages/" + page + "/share-link", null, team.owner);
        assertEquals(CloseCodes.FORBIDDEN, visitor.closed.get(1, TimeUnit.SECONDS));
        System.out.printf("링크를 끈 뒤 %dms 에 닫혔다%n", (System.nanoTime() - disabledAt) / 1_000_000);
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
