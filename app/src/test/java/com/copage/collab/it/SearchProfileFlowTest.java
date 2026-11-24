package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.identity.domain.CursorColors;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import com.copage.support.TestInfra;
import com.copage.workspace.app.InviteService;
import com.copage.workspace.app.WorkspaceService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 검색 (F-1, F-2), 프로필의 커서 색 (A-3), 휴지통 비우기 (W-4) 를 실제 서버로 확인한다 (D-47). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchProfileFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    InviteService inviteService;

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

    private Account account(String name) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", name), null)
                .body();
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null).body();
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }

    private String page(String workspace, String parentId, Account who) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", parentId);
        return send("POST", "/api/workspaces/" + workspace + "/pages", body, who).body().get("id").stringValue();
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

    /** 루트(제목)와 그 아래 문단 하나를 쓴다. */
    private static void write(TestClient client, String page, String title, String body) throws Exception {
        client.edit(List.of(
                new TextOp(page, Delta.builder().insert(title).build()),
                new InsertBlock("p-" + UUID.randomUUID(), page, "a", "paragraph", Attrs.NONE,
                        RichText.of(body))));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
    }

    private List<String> titles(String workspace, String query, Account who) throws Exception {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        List<String> titles = new ArrayList<>();
        for (JsonNode hit : send("GET", "/api/workspaces/" + workspace + "/search?q=" + q, null, who).body()) {
            titles.add(hit.get("title").stringValue());
        }
        return titles;
    }

    @Test
    void 두_글자로도_제목과_본문을_찾고_볼_수_없는_페이지는_나오지_않는다() throws Exception {
        Account owner = account("주인");
        Account member = account("멤버");
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
        send("POST", "/api/workspaces/" + workspace + "/members", Map.of("email", member.email(), "role", "MEMBER"), owner);

        String inBody = page(workspace, null, owner);
        String inTitle = page(workspace, null, owner);
        String secret = page(workspace, null, owner);
        TestClient client = open(inBody, owner);
        write(client, inBody, "주간 정리", "오늘 회의에서 정한 것 100% 반영");
        write(open(inTitle, owner), inTitle, "회의록", "안건");
        write(open(secret, owner), secret, "비밀 회의", "");
        send("PUT", "/api/pages/" + secret + "/restricted", Map.of("restricted", true), owner);

        // 본문은 세션이 몇 초 안에 알린다.
        long deadline = System.currentTimeMillis() + 15_000;
        while (titles(workspace, "회의", owner).size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        // 제목에 든 것이 먼저다. 제목에 든 둘의 순서는 마지막으로 색인된 때를 따라 정해지지 않는다.
        List<String> found = titles(workspace, "회의", owner);
        assertEquals(Set.of("비밀 회의", "회의록"), Set.copyOf(found.subList(0, 2)));
        assertEquals("주간 정리", found.get(2), "본문에만 든 것은 뒤에");
        assertEquals(List.of("회의록", "주간 정리"), titles(workspace, "회의", member), "제한된 페이지는 제목도 나오지 않는다");
        JsonNode hit = send("GET", "/api/workspaces/" + workspace + "/search?q=%EC%A0%95%ED%95%9C", null, member).body()
                .get(0);
        assertEquals(inBody, hit.get("id").stringValue());
        assertTrue(hit.get("snippet").stringValue().contains("정한 것"));
        // LIKE 의 와일드카드는 글자 그대로 찾는다.
        assertEquals(List.of("주간 정리"), titles(workspace, "100%", member));
        assertEquals(List.of(), titles(workspace, "%", member).stream().filter(t -> !t.equals("주간 정리")).toList());
        assertEquals(List.of(), titles(workspace, "  ", member));
        assertEquals(404, send("GET", "/api/workspaces/" + workspace + "/search?q=x", null, account("남")).status());

        // 휴지통의 페이지와 그 하위는 나오지 않는다.
        page(workspace, inTitle, owner);
        send("DELETE", "/api/pages/" + inTitle, null, owner);
        assertEquals(List.of("주간 정리"), titles(workspace, "회의", member));
    }

    @Test
    void 프로필에서_고른_커서_색이_남의_화면에_보인다() throws Exception {
        Account owner = account("주인");
        Account other = account("남");
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
        send("POST", "/api/workspaces/" + workspace + "/members", Map.of("email", other.email(), "role", "MEMBER"), owner);
        String page = page(workspace, null, owner);

        String color = CursorColors.PALETTE.stream().filter(c -> !c.equals(CursorColors.byId(owner.id()))).findFirst()
                .orElseThrow();
        Response changed = send("PATCH", "/api/me", Map.of("color", color), owner);
        assertEquals(200, changed.status());
        assertEquals(color, changed.body().get("color").stringValue());
        assertEquals("주인", changed.body().get("name").stringValue(), "이름은 그대로");
        assertEquals(400, send("PATCH", "/api/me", Map.of("color", "#000000"), owner).status());
        assertEquals(400, send("PATCH", "/api/me", Map.of(), owner).status());
        assertEquals(400, send("PATCH", "/api/me", Map.of("name", " "), owner).status());
        assertEquals("새 이름", send("PATCH", "/api/me", Map.of("name", "새 이름"), owner).body().get("name").stringValue());
        assertEquals(color, send("GET", "/api/me", null, owner).body().get("color").stringValue());

        String ownerTicket = send("POST", "/api/docs/" + page + "/ws-ticket", null, owner).body().get("ticket")
                .stringValue();
        RawClient first = new RawClient(false);
        first.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ownerTicket + "&v=1"),
                "owner-tab");
        String otherTicket = send("POST", "/api/docs/" + page + "/ws-ticket", null, other).body().get("ticket")
                .stringValue();
        JsonNode welcome = new RawClient(false).connect(
                URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + otherTicket + "&v=1"),
                "other-tab");
        JsonNode peer = welcome.get("peers").get(0);
        assertEquals("새 이름", peer.get("name").stringValue());
        assertEquals(color, peer.get("color").stringValue());
        assertEquals(CursorColors.byId(other.id()),
                first.await("presence", 3_000).message().get("color").stringValue(), "고르지 않으면 ID 로 정한 색");
    }

    @Test
    void 휴지통에서_30일이_지난_페이지는_하위와_기록까지_지우고_만료된_초대도_지운다() throws Exception {
        Account owner = account("주인");
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
        String old = page(workspace, null, owner);
        String child = page(workspace, old, owner);
        String recent = page(workspace, null, owner);
        write(open(child, owner), child, "하위", "글");
        send("DELETE", "/api/pages/" + old, null, owner);
        send("DELETE", "/api/pages/" + recent, null, owner);
        jdbc.sql("UPDATE pages SET archived_at = :at WHERE id = :id")
                .param("at", Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)))
                .param("id", UUID.fromString(old))
                .update();
        String invite = send("POST", "/api/workspaces/" + workspace + "/invites",
                Map.of("email", "x-" + UUID.randomUUID() + "@example.com", "role", "MEMBER"), owner).body().get("id")
                .stringValue();
        jdbc.sql("UPDATE workspace_invites SET expires_at = :at WHERE id = :id")
                .param("at", Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)))
                .param("id", UUID.fromString(invite))
                .update();

        assertTrue(workspaceService.purgeTrash(Duration.ofDays(30)) >= 2);
        assertTrue(inviteService.purgeExpired() >= 1);

        assertEquals(0, count("SELECT count(*) FROM pages WHERE id IN (:ids)", List.of(old, child)));
        assertEquals(0, count("SELECT count(*) FROM doc_ops WHERE doc_id IN (:ids)", List.of(old, child)));
        assertEquals(1, count("SELECT count(*) FROM pages WHERE id IN (:ids)", List.of(recent)), "30일이 안 된 것은 남는다");
        assertEquals(0, count("SELECT count(*) FROM workspace_invites WHERE id IN (:ids)", List.of(invite)));
        JsonNode trash = send("GET", "/api/workspaces/" + workspace + "/trash", null, owner).body();
        assertEquals(1, trash.size());
        assertEquals(recent, trash.get(0).get("id").stringValue());
    }

    private long count(String sql, List<String> ids) {
        return jdbc.sql(sql).param("ids", ids.stream().map(UUID::fromString).toList()).query(Long.class).single();
    }
}
