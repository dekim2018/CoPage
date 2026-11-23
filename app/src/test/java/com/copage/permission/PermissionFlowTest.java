package com.copage.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.collab.it.TestClient;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
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

/**
 * 티켓과 페이지 API 가 페이지 권한(docs/07-SECURITY.md 9.4)을 지키는지 실제 서버로 확인한다. 권한 부여와 restricted 를 바꾸는
 * API 는 아직 없어 DB 에 직접 넣는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionFlowTest extends TestInfra {

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

    /** 워크스페이스 하나와 그 멤버들. */
    private final class Team {
        final Account owner = account();
        final Account member = account();
        final Account guest = account();
        final Account stranger = account();
        final String id;

        Team() throws Exception {
            id = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
            send("POST", "/api/workspaces/" + id + "/members", Map.of("email", member.email(), "role", "MEMBER"), owner);
            send("POST", "/api/workspaces/" + id + "/members", Map.of("email", guest.email(), "role", "GUEST"), owner);
        }

        String page(String parentId) throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("parentId", parentId);
            Response created = send("POST", "/api/workspaces/" + id + "/pages", body, owner);
            assertEquals(201, created.status());
            return created.body().get("id").stringValue();
        }
    }

    private void grant(String pageId, Account to, String level, Account by) {
        jdbc.sql("INSERT INTO page_grants (page_id, user_id, level, granted_by) VALUES (:p, :u, :l, :by)")
                .param("p", UUID.fromString(pageId)).param("u", to.id()).param("l", level).param("by", by.id())
                .update();
    }

    private void restrict(String pageId) {
        jdbc.sql("UPDATE pages SET restricted = true WHERE id = :id").param("id", UUID.fromString(pageId)).update();
    }

    private Response ticket(String pageId, Account who) throws Exception {
        return send("POST", "/api/docs/" + pageId + "/ws-ticket", null, who);
    }

    private List<String> children(Team team, String parentId, Account who) throws Exception {
        Response list = send("GET", "/api/workspaces/" + team.id + "/pages"
                + (parentId == null ? "" : "?parentId=" + parentId), null, who);
        assertEquals(200, list.status());
        List<String> ids = new ArrayList<>();
        list.body().forEach(node -> ids.add(node.get("id").stringValue() + ":" + node.get("level").stringValue()));
        return ids;
    }

    @Test
    void 티켓은_페이지가_없거나_속하지_않으면_404_권한이_없으면_403_이고_등급을_담는다() throws Exception {
        Team team = new Team();
        String page = team.page(null);

        assertEquals(404, ticket(UUID.randomUUID().toString(), team.owner).status(), "페이지가 없는 문서 ID 로는 열 수 없다");
        assertEquals(404, ticket(page, team.stranger).status());
        assertEquals(403, ticket(page, team.guest).status());
        assertEquals("FULL", ticket(page, team.owner).body().get("role").stringValue());
        assertEquals("EDIT", ticket(page, team.member).body().get("role").stringValue());

        grant(page, team.guest, "VIEW", team.owner);
        Response viewer = ticket(page, team.guest);
        assertEquals(200, viewer.status());
        assertEquals("VIEW", viewer.body().get("role").stringValue());
        // 세션도 그 등급으로 받는다.
        TestClient client = new TestClient("guest-tab");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket="
                + viewer.body().get("ticket").stringValue() + "&v=1"));
        assertEquals("VIEW", client.take().get("role").stringValue());
    }

    @Test
    void 제한된_페이지는_멤버에게도_막히고_부여받으면_열린다() throws Exception {
        Team team = new Team();
        String root = team.page(null);
        String secret = team.page(root);
        String inner = team.page(secret);
        restrict(secret);

        assertEquals(403, ticket(inner, team.member).status());
        assertEquals("FULL", ticket(inner, team.owner).body().get("role").stringValue());
        // 목록에서도 빠진다.
        assertEquals(List.of(), children(team, root, team.member));
        assertEquals(List.of(secret + ":FULL"), children(team, root, team.owner));

        grant(secret, team.member, "COMMENT", team.owner);
        assertEquals("COMMENT", ticket(inner, team.member).body().get("role").stringValue());
        assertEquals(List.of(secret + ":COMMENT"), children(team, root, team.member));
    }

    @Test
    void 게스트는_받은_페이지만_보고_EDIT_를_받은_곳에만_만든다() throws Exception {
        Team team = new Team();
        String shared = team.page(null);
        String other = team.page(null);

        assertEquals(List.of(), children(team, null, team.guest));
        grant(shared, team.guest, "VIEW", team.owner);
        assertEquals(List.of(shared + ":VIEW"), children(team, null, team.guest));
        assertEquals(404, send("GET", "/api/workspaces/" + team.id + "/pages?parentId=" + other, null, team.guest)
                .status(), "볼 수 없는 부모의 하위 목록은 없는 것과 같다");

        Map<String, Object> under = new HashMap<>();
        under.put("parentId", shared);
        assertEquals(403, send("POST", "/api/workspaces/" + team.id + "/pages", under, team.guest).status());
        assertEquals(403, send("POST", "/api/workspaces/" + team.id + "/pages", Map.of(), team.guest).status());
        jdbc.sql("UPDATE page_grants SET level = 'EDIT' WHERE page_id = :p AND user_id = :u")
                .param("p", UUID.fromString(shared)).param("u", team.guest.id()).update();
        Response created = send("POST", "/api/workspaces/" + team.id + "/pages", under, team.guest);
        assertEquals(201, created.status());
        assertEquals("EDIT", created.body().get("level").stringValue());
    }

    @Test
    void 페이지_조회는_내_등급과_볼_수_있는_경로를_준다() throws Exception {
        Team team = new Team();
        String root = team.page(null);
        String child = team.page(root);
        String leaf = team.page(child);

        JsonNode forMember = send("GET", "/api/pages/" + leaf, null, team.member).body();
        assertEquals("EDIT", forMember.get("level").stringValue());
        assertEquals(team.id, forMember.get("workspaceId").stringValue());
        assertEquals(child, forMember.get("parentId").stringValue());
        assertEquals(List.of(root, child), ids(forMember.get("path")));

        grant(leaf, team.guest, "VIEW", team.owner);
        JsonNode forGuest = send("GET", "/api/pages/" + leaf, null, team.guest).body();
        assertEquals("VIEW", forGuest.get("level").stringValue());
        assertEquals(List.of(), ids(forGuest.get("path")), "공유받지 않은 조상은 경로에 없다");

        assertEquals(403, send("GET", "/api/pages/" + root, null, team.guest).status());
        assertEquals(404, send("GET", "/api/pages/" + root, null, team.stranger).status());
        assertEquals(404, send("GET", "/api/pages/" + UUID.randomUUID(), null, team.owner).status());
    }

    private static List<String> ids(JsonNode path) {
        List<String> ids = new ArrayList<>();
        path.forEach(entry -> ids.add(entry.get("id").stringValue()));
        return ids;
    }
}
