package com.copage.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.permission.app.PermissionChange;
import com.copage.permission.app.PermissionChangeListener;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 권한 부여, 회수, restricted, 멤버 역할 변경과 내보내기 (docs/09-API.md 11.2, 11.3) 를 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrantFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    /** 알린 권한 변경을 모은다. */
    @TestConfiguration
    static class Changes {
        static final List<PermissionChange> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        PermissionChangeListener recordingListener() {
            return SEEN::add;
        }
    }

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
            assertEquals(201, send("POST", "/api/workspaces/" + id + "/members",
                    Map.of("email", member.email(), "role", "MEMBER"), owner).status());
        }

        String page(String parentId) throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("parentId", parentId);
            return send("POST", "/api/workspaces/" + id + "/pages", body, owner).body().get("id").stringValue();
        }
    }

    private Response grant(String pageId, Account to, String level, Account by) throws Exception {
        return send("PUT", "/api/pages/" + pageId + "/grants/" + to.id(), Map.of("level", level), by);
    }

    private int ticket(String pageId, Account who) throws Exception {
        return send("POST", "/api/docs/" + pageId + "/ws-ticket", null, who).status();
    }

    private String ticketRole(String pageId, Account who) throws Exception {
        return send("POST", "/api/docs/" + pageId + "/ws-ticket", null, who).body().get("role").stringValue();
    }

    private PermissionChange lastChange() {
        return Changes.SEEN.get(Changes.SEEN.size() - 1);
    }

    private static Set<UUID> ids(String... ids) {
        Set<UUID> set = new HashSet<>();
        for (String id : ids) {
            set.add(UUID.fromString(id));
        }
        return set;
    }

    @Test
    void 공유_설정은_FULL_만_바꾼다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        Account stranger = account();

        assertEquals(403, grant(page, stranger, "VIEW", team.member).status(), "MEMBER 의 기본 권한은 EDIT 다");
        assertEquals(404, grant(page, team.member, "VIEW", stranger).status());
        assertEquals(403, send("PUT", "/api/pages/" + page + "/restricted", Map.of("restricted", true), team.member)
                .status());
        assertEquals(400, send("PUT", "/api/pages/" + page + "/grants/" + stranger.id(), Map.of("level", "KING"),
                team.owner).status());

        // FULL 을 받으면 공유 설정을 바꿀 수 있다.
        assertEquals(200, grant(page, team.member, "FULL", team.owner).status());
        assertEquals(200, grant(page, stranger, "VIEW", team.member).status());
    }

    @Test
    void 멤버가_아닌_사람에게_주면_GUEST_로_들어오고_그_페이지와_하위만_연다() throws Exception {
        Team team = new Team();
        String root = team.page(null);
        String child = team.page(root);
        String other = team.page(null);
        Account outsider = account();

        Response granted = send("POST", "/api/pages/" + root + "/grants",
                Map.of("email", outsider.email().toUpperCase(), "level", "COMMENT"), team.owner);
        assertEquals(200, granted.status(), String.valueOf(granted.body()));

        assertEquals("GUEST", send("GET", "/api/workspaces", null, outsider).body().get(0).get("role").stringValue());
        assertEquals("COMMENT", ticketRole(child, outsider));
        assertEquals(403, ticket(other, outsider));
        // 알림은 그 페이지와 하위, 그 사람이다.
        assertEquals(new PermissionChange(ids(root, child), outsider.id()), lastChange());

        assertEquals(404, send("POST", "/api/pages/" + root + "/grants",
                Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "level", "VIEW"), team.owner).status());
    }

    @Test
    void 권한_목록은_이_페이지에_준_것과_경계까지_물려받는_것을_보여_준다() throws Exception {
        Team team = new Team();
        String root = team.page(null);
        String middle = team.page(root);
        String leaf = team.page(middle);
        Account a = account();
        Account b = account();
        Account c = account();
        grant(root, a, "VIEW", team.owner);
        grant(middle, b, "EDIT", team.owner);
        grant(leaf, c, "FULL", team.owner);

        JsonNode list = send("GET", "/api/pages/" + leaf + "/grants", null, team.owner).body();
        Map<String, String> inherited = new HashMap<>();
        list.forEach(row -> inherited.put(row.get("email").stringValue(),
                row.get("inheritedFrom").isNull() ? "-" : row.get("inheritedFrom").stringValue()));
        assertEquals(Map.of(c.email(), "-", b.email(), middle, a.email(), root), inherited);
        assertEquals(c.email(), list.get(0).get("email").stringValue(), "이 페이지에 준 것이 먼저다");

        // middle 을 제한하면 root 의 부여는 더 이상 물려받지 않는다.
        send("PUT", "/api/pages/" + middle + "/restricted", Map.of("restricted", true), team.owner);
        List<String> emails = new ArrayList<>();
        send("GET", "/api/pages/" + leaf + "/grants", null, team.owner).body()
                .forEach(row -> emails.add(row.get("email").stringValue()));
        assertEquals(List.of(c.email(), b.email()), emails);
    }

    @Test
    void 회수하면_티켓을_받지_못한다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        Account guest = account();
        grant(page, guest, "EDIT", team.owner);
        assertEquals(200, ticket(page, guest));

        assertEquals(204, send("DELETE", "/api/pages/" + page + "/grants/" + guest.id(), null, team.owner).status());
        assertEquals(403, ticket(page, guest));
        assertEquals(new PermissionChange(ids(page), guest.id()), lastChange());
        assertEquals(404, send("DELETE", "/api/pages/" + page + "/grants/" + guest.id(), null, team.owner).status());
    }

    @Test
    void 물려받은_FULL_로_제한한_사람은_그_페이지의_FULL_을_남긴다() throws Exception {
        Team team = new Team();
        Account other = account();
        send("POST", "/api/workspaces/" + team.id + "/members", Map.of("email", other.email(), "role", "MEMBER"),
                team.owner);
        String root = team.page(null);
        String child = team.page(root);
        String grandchild = team.page(child);
        grant(root, team.member, "FULL", team.owner);

        Response restricted = send("PUT", "/api/pages/" + child + "/restricted", Map.of("restricted", true), team.member);
        assertEquals(200, restricted.status());
        assertEquals(true, send("GET", "/api/pages/" + child, null, team.member).body().get("restricted").booleanValue());

        assertEquals("FULL", ticketRole(grandchild, team.member), "경계 위의 FULL 이 끊겨도 잠기지 않는다");
        assertEquals(403, ticket(grandchild, other), "다른 멤버는 기본 권한을 잃는다");
        assertEquals("FULL", ticketRole(grandchild, team.owner));
        assertEquals(new PermissionChange(ids(child, grandchild), null), lastChange());

        // 다시 이으면 기본 권한이 돌아온다.
        send("PUT", "/api/pages/" + child + "/restricted", Map.of("restricted", false), team.member);
        assertEquals("EDIT", ticketRole(grandchild, other));
    }

    @Test
    void 역할을_내리거나_내보내면_페이지_접근이_바뀌고_권한도_지운다() throws Exception {
        Team team = new Team();
        String page = team.page(null);
        String path = "/api/workspaces/" + team.id + "/members/" + team.member.id();

        assertEquals(204, send("PATCH", path, Map.of("role", "GUEST"), team.owner).status());
        assertEquals(403, ticket(page, team.member));
        assertEquals(PermissionChange.allPagesOf(team.member.id()), lastChange());

        grant(page, team.member, "VIEW", team.owner);
        assertEquals(204, send("DELETE", path, null, team.owner).status());
        assertEquals(404, ticket(page, team.member));
        // 다시 넣어도 옛 권한은 되살아나지 않는다.
        send("POST", "/api/workspaces/" + team.id + "/members", Map.of("email", team.member.email(), "role", "GUEST"),
                team.owner);
        assertEquals(403, ticket(page, team.member));
        assertEquals(404, send("DELETE", "/api/workspaces/" + team.id + "/members/" + UUID.randomUUID(), null,
                team.owner).status());
        assertEquals(403, send("PATCH", "/api/workspaces/" + team.id + "/members/" + team.owner.id(),
                Map.of("role", "MEMBER"), team.member).status(), "OWNER 만 바꾼다");
    }

    @Test
    void 마지막_OWNER_는_내리거나_내보낼_수_없고_둘이_서로를_동시에_내려도_하나는_남는다() throws Exception {
        Team team = new Team();
        String self = "/api/workspaces/" + team.id + "/members/" + team.owner.id();
        assertEquals(409, send("PATCH", self, Map.of("role", "MEMBER"), team.owner).status());
        assertEquals(409, send("DELETE", self, null, team.owner).status());

        send("PATCH", "/api/workspaces/" + team.id + "/members/" + team.member.id(), Map.of("role", "OWNER"), team.owner);
        // 결과만 본다. 두 요청이 정말 겹치는지는 시간에 달려 있어, 잠금을 잡는지는 JdbcWorkspaceRepositoryTest 가 따로 확인한다.
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Response> a = pool.submit(() -> send("PATCH", "/api/workspaces/" + team.id + "/members/"
                    + team.member.id(), Map.of("role", "MEMBER"), team.owner));
            Future<Response> b = pool.submit(() -> send("PATCH", self, Map.of("role", "MEMBER"), team.member));
            List<Integer> statuses = new ArrayList<>(List.of(a.get().status(), b.get().status()));
            statuses.sort(null);
            // 하나만 바뀐다. 다른 하나는 마지막 OWNER 라서(409), 또는 먼저 커밋된 쪽에 이미 내려가 OWNER 가 아니어서(403) 실패한다.
            assertEquals(204, statuses.get(0));
            assertTrue(statuses.get(1) == 403 || statuses.get(1) == 409, statuses.toString());
        }
        assertEquals(1, jdbc.sql("SELECT count(*) FROM workspace_members WHERE workspace_id = :id AND role = 'OWNER'")
                .param("id", UUID.fromString(team.id)).query(Integer.class).single());
    }
}
