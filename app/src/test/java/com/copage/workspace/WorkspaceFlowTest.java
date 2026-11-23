package com.copage.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 워크스페이스, 멤버, 페이지 API 를 실제 서버와 PostgreSQL 로 확인한다 (docs/09-API.md 11.2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body) {
        String id() {
            return body.get("id").stringValue();
        }
    }

    /** 가입하고 로그인한 사용자. */
    private record Account(String email, String token) {
    }

    private Response send(String method, String path, Object json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(json)));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        return new Response(response.statusCode(), isJson && !response.body().isEmpty() ? MAPPER.readTree(response.body()) : null);
    }

    private Account account() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"), null)
                .status());
        Response login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null);
        return new Account(email, login.body().get("accessToken").stringValue());
    }

    private String workspace(Account owner) throws Exception {
        Response created = send("POST", "/api/workspaces", Map.of("name", " 우리 팀 "), owner.token());
        assertEquals(201, created.status());
        return created.id();
    }

    private Response page(Account who, String workspaceId, String parentId, String afterId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", parentId);
        body.put("afterId", afterId);
        return send("POST", "/api/workspaces/" + workspaceId + "/pages", body, who.token());
    }

    private List<String> pageIds(Account who, String workspaceId, String parentId) throws Exception {
        Response list = send("GET", "/api/workspaces/" + workspaceId + "/pages"
                + (parentId == null ? "" : "?parentId=" + parentId), null, who.token());
        assertEquals(200, list.status());
        List<String> ids = new ArrayList<>();
        list.body().forEach(node -> ids.add(node.get("id").stringValue()));
        return ids;
    }

    private Response addMember(Account who, String workspaceId, String email, String role) throws Exception {
        return send("POST", "/api/workspaces/" + workspaceId + "/members", Map.of("email", email, "role", role),
                who.token());
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        assertEquals(401, send("GET", "/api/workspaces", null, null).status());
    }

    @Test
    void 만든_사람은_OWNER_로_보고_페이지_트리를_만든다() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);

        JsonNode mine = send("GET", "/api/workspaces", null, owner.token()).body();
        assertEquals(1, mine.size());
        assertEquals("우리 팀", mine.get(0).get("name").stringValue());
        assertEquals("OWNER", mine.get(0).get("role").stringValue());

        // 빈 본문이면 최상위 맨 뒤에 만든다.
        Response first = send("POST", "/api/workspaces/" + workspaceId + "/pages", null, owner.token());
        assertEquals(201, first.status());
        assertTrue(first.body().get("parentId").isNull());
        String last = page(owner, workspaceId, null, null).id();
        String middle = page(owner, workspaceId, null, first.id()).id();
        String child = page(owner, workspaceId, first.id(), null).id();

        assertEquals(List.of(first.id(), middle, last), pageIds(owner, workspaceId, null));
        assertEquals(List.of(child), pageIds(owner, workspaceId, first.id()));
        JsonNode top = send("GET", "/api/workspaces/" + workspaceId + "/pages", null, owner.token()).body();
        assertTrue(top.get(0).get("hasChildren").booleanValue());
        assertFalse(top.get(1).get("hasChildren").booleanValue());
    }

    @Test
    void 속하지_않은_사람에게는_워크스페이스가_없는_것과_같다() throws Exception {
        Account owner = account();
        Account stranger = account();
        String workspaceId = workspace(owner);
        String pageId = page(owner, workspaceId, null, null).id();

        assertEquals(0, send("GET", "/api/workspaces", null, stranger.token()).body().size());
        assertEquals(404, send("GET", "/api/workspaces/" + workspaceId + "/pages", null, stranger.token()).status());
        assertEquals(404, send("GET", "/api/workspaces/" + workspaceId + "/members", null, stranger.token()).status());
        assertEquals(404, page(stranger, workspaceId, null, null).status());
        assertEquals(404, page(stranger, workspaceId, pageId, null).status());
        assertEquals(404, addMember(stranger, workspaceId, stranger.email(), "MEMBER").status());
        // 없는 워크스페이스도 같은 응답이다.
        assertEquals(404, send("GET", "/api/workspaces/" + UUID.randomUUID() + "/pages", null, stranger.token())
                .status());
    }

    @Test
    void OWNER_가_가입한_사용자를_이메일로_넣으면_역할대로_페이지를_다룬다() throws Exception {
        Account owner = account();
        Account member = account();
        Account guest = account();
        String workspaceId = workspace(owner);
        String pageId = page(owner, workspaceId, null, null).id();

        // 이메일은 대소문자를 맞춰 찾는다. 앞뒤 공백은 가입처럼 형식 검사에서 거절한다.
        Response added = addMember(owner, workspaceId, member.email().toUpperCase(), "MEMBER");
        assertEquals(201, added.status(), String.valueOf(added.body()));
        assertEquals(member.email(), added.body().get("email").stringValue());
        assertEquals(201, addMember(owner, workspaceId, guest.email(), "GUEST").status());

        // MEMBER 는 모든 페이지를 보고 만든다.
        assertEquals(List.of(pageId), pageIds(member, workspaceId, null));
        assertEquals(201, page(member, workspaceId, pageId, null).status());
        JsonNode members = send("GET", "/api/workspaces/" + workspaceId + "/members", null, member.token()).body();
        assertEquals(3, members.size());
        assertEquals("OWNER", members.get(0).get("role").stringValue());
        // MEMBER 는 멤버를 넣지 못한다.
        assertEquals(403, addMember(member, workspaceId, account().email(), "MEMBER").status());

        // GUEST 는 권한을 받은 페이지만 본다. 아직 권한 부여가 없으므로 아무것도 보이지 않는다.
        assertEquals(List.of(), pageIds(guest, workspaceId, null));
        assertEquals(403, page(guest, workspaceId, null, null).status());
        assertEquals(403, send("GET", "/api/workspaces/" + workspaceId + "/members", null, guest.token()).status());
        assertEquals("GUEST", send("GET", "/api/workspaces", null, guest.token()).body().get(0).get("role").stringValue());
    }

    @Test
    void 멤버_추가의_잘못된_요청() throws Exception {
        Account owner = account();
        Account member = account();
        String workspaceId = workspace(owner);
        assertEquals(201, addMember(owner, workspaceId, member.email(), "MEMBER").status());

        assertEquals(409, addMember(owner, workspaceId, member.email(), "GUEST").status());
        assertEquals(404, addMember(owner, workspaceId, "nobody-" + UUID.randomUUID() + "@example.com", "MEMBER")
                .status());
        assertEquals(400, addMember(owner, workspaceId, account().email(), "OWNER").status());
        assertEquals(400, addMember(owner, workspaceId, "not-an-email", "MEMBER").status());
        assertEquals(400, addMember(owner, workspaceId, member.email(), "KING").status());
    }

    @Test
    void 페이지와_워크스페이스의_잘못된_요청() throws Exception {
        Account owner = account();
        String workspaceId = workspace(owner);
        String parent = page(owner, workspaceId, null, null).id();
        String child = page(owner, workspaceId, parent, null).id();

        assertEquals(400, send("POST", "/api/workspaces", Map.of("name", " "), owner.token()).status());
        assertEquals(404, page(owner, workspaceId, UUID.randomUUID().toString(), null).status());
        assertEquals(400, page(owner, workspaceId, null, child).status(), "다른 부모의 하위는 앞 형제가 아니다");
        assertEquals(404, send("GET", "/api/workspaces/" + workspaceId + "/pages?parentId=" + UUID.randomUUID(), null,
                owner.token()).status());
        assertEquals(400, send("GET", "/api/workspaces/not-a-uuid/pages", null, owner.token()).status());
    }
}
