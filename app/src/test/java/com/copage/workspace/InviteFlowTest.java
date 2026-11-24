package com.copage.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 워크스페이스 초대 (docs/09-API.md 11.2, D-40) 를 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteFlowTest extends TestInfra {

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
        return account("u-" + UUID.randomUUID() + "@example.com");
    }

    private Account account(String email) throws Exception {
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"), null)
                .body();
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null).body();
        return new Account(UUID.fromString(user.get("id").stringValue()), user.get("email").stringValue(),
                login.get("accessToken").stringValue());
    }

    private String workspace(Account owner) throws Exception {
        return send("POST", "/api/workspaces", Map.of("name", "초대하는 팀"), owner).body().get("id").stringValue();
    }

    private Response invite(String workspaceId, String email, String role, Account by) throws Exception {
        return send("POST", "/api/workspaces/" + workspaceId + "/invites", Map.of("email", email, "role", role), by);
    }

    private String role(String workspaceId, Account who) throws Exception {
        for (JsonNode workspace : send("GET", "/api/workspaces", null, who).body()) {
            if (workspace.get("id").stringValue().equals(workspaceId)) {
                return workspace.get("role").stringValue();
            }
        }
        return null;
    }

    @Test
    void 초대받은_이메일의_사용자가_링크로_받으면_그_역할로_들어간다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        String email = "Invited-" + UUID.randomUUID() + "@Example.com";

        Response created = invite(workspaceId, email, "MEMBER", owner);
        assertEquals(201, created.status());
        String token = created.body().get("token").stringValue();
        assertEquals(email.toLowerCase(), created.body().get("email").stringValue(), "이메일은 정규화한다");
        // 대기 목록에는 토큰이 없다.
        JsonNode pending = send("GET", "/api/workspaces/" + workspaceId + "/invites", null, owner).body();
        assertEquals(1, pending.size());
        assertFalse(pending.get(0).has("token"));
        // 저장소에는 해시만 있다.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM workspace_invites WHERE token_hash = :token")
                .param("token", token).query(Long.class).single());

        Account invited = account(email.toLowerCase());
        Response preview = send("GET", "/api/invites/" + token, null, invited);
        assertEquals(200, preview.status());
        assertEquals("초대하는 팀", preview.body().get("workspaceName").stringValue());

        Response accepted = send("POST", "/api/invites/" + token + "/accept", null, invited);
        assertEquals(200, accepted.status());
        assertEquals(workspaceId, accepted.body().get("workspaceId").stringValue());
        assertEquals("MEMBER", role(workspaceId, invited));
        assertEquals(409, send("POST", "/api/invites/" + token + "/accept", null, invited).status(), "한 번만 받는다");
        assertEquals(404, send("GET", "/api/invites/" + token, null, invited).status());
        assertEquals(0, send("GET", "/api/workspaces/" + workspaceId + "/invites", null, owner).body().size());
    }

    @Test
    void 다른_이메일의_사용자는_링크가_있어도_받을_수_없다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        String token = invite(workspaceId, "someone-" + UUID.randomUUID() + "@example.com", "MEMBER", owner).body()
                .get("token").stringValue();
        Account other = account();

        assertEquals(403, send("POST", "/api/invites/" + token + "/accept", null, other).status());
        assertEquals(null, role(workspaceId, other));
        assertEquals(1, send("GET", "/api/workspaces/" + workspaceId + "/invites", null, owner).body().size(),
                "쓰이지 않았다");
    }

    @Test
    void 초대는_OWNER_만_만들고_취소한다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        Account member = account();
        send("POST", "/api/workspaces/" + workspaceId + "/members", Map.of("email", member.email(), "role", "MEMBER"),
                owner);
        Account stranger = account();

        assertEquals(403, invite(workspaceId, "x@example.com", "MEMBER", member).status());
        assertEquals(404, invite(workspaceId, "x@example.com", "MEMBER", stranger).status());
        assertEquals(400, invite(workspaceId, "x@example.com", "OWNER", owner).status());
        assertEquals(400, invite(workspaceId, "not-an-email", "MEMBER", owner).status());

        Response created = invite(workspaceId, "x@example.com", "GUEST", owner);
        String inviteId = created.body().get("id").stringValue();
        assertEquals(403, send("DELETE", "/api/workspaces/" + workspaceId + "/invites/" + inviteId, null, member)
                .status());
        assertEquals(204, send("DELETE", "/api/workspaces/" + workspaceId + "/invites/" + inviteId, null, owner)
                .status());
        assertEquals(404, send("GET", "/api/invites/" + created.body().get("token").stringValue(), null, member)
                .status(), "취소한 초대는 없다");
    }

    @Test
    void 만료된_초대는_받을_수_없다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        Account invited = account();
        String token = "expired-" + UUID.randomUUID();
        Instant past = Instant.now().minus(8, ChronoUnit.DAYS);
        jdbc.sql("""
                        INSERT INTO workspace_invites
                            (id, workspace_id, email, role, token_hash, created_by, created_at, expires_at)
                        VALUES (:id, :workspaceId, :email, 'MEMBER', :hash, :owner, :createdAt, :expiresAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", UUID.fromString(workspaceId))
                .param("email", invited.email())
                .param("hash", sha256(token))
                .param("owner", owner.id())
                .param("createdAt", Timestamp.from(past))
                .param("expiresAt", Timestamp.from(past.plus(7, ChronoUnit.DAYS)))
                .update();

        assertEquals(404, send("GET", "/api/invites/" + token, null, invited).status());
        assertEquals(404, send("POST", "/api/invites/" + token + "/accept", null, invited).status());
        assertEquals(0, send("GET", "/api/workspaces/" + workspaceId + "/invites", null, owner).body().size());
        assertEquals(null, role(workspaceId, invited));
    }

    @Test
    void 이미_멤버면_역할을_내리지_않고_GUEST_는_MEMBER_초대로_올린다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        Account member = account();
        send("POST", "/api/workspaces/" + workspaceId + "/members", Map.of("email", member.email(), "role", "MEMBER"),
                owner);
        Account guest = account();
        send("POST", "/api/workspaces/" + workspaceId + "/members", Map.of("email", guest.email(), "role", "GUEST"),
                owner);

        String down = invite(workspaceId, member.email(), "GUEST", owner).body().get("token").stringValue();
        assertEquals(200, send("POST", "/api/invites/" + down + "/accept", null, member).status());
        assertEquals("MEMBER", role(workspaceId, member));

        String up = invite(workspaceId, guest.email(), "MEMBER", owner).body().get("token").stringValue();
        assertEquals(200, send("POST", "/api/invites/" + up + "/accept", null, guest).status());
        assertEquals("MEMBER", role(workspaceId, guest));
    }

    @Test
    void 같은_초대를_동시에_받아도_한_번만_받는다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        Account invited = account();
        String token = invite(workspaceId, invited.email(), "MEMBER", owner).body().get("token").stringValue();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return send("POST", "/api/invites/" + token + "/accept", null, invited).status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
            assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 409), statuses.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("MEMBER", role(workspaceId, invited));
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
