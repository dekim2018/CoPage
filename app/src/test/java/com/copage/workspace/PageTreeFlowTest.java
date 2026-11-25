package com.copage.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 페이지 옮기기, 휴지통으로 보내기와 꺼내기, 휴지통 목록 (docs/09-API.md 11.2) 을 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageTreeFlowTest extends TestInfra {

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

        List<String> children(String parentId, Account who) throws Exception {
            String query = parentId == null ? "" : "?parentId=" + parentId;
            List<String> ids = new ArrayList<>();
            send("GET", "/api/workspaces/" + id + "/pages" + query, null, who).body()
                    .forEach(page -> ids.add(page.get("id").stringValue()));
            return ids;
        }

        List<String> trash(Account who) throws Exception {
            List<String> ids = new ArrayList<>();
            send("GET", "/api/workspaces/" + id + "/trash", null, who).body()
                    .forEach(page -> ids.add(page.get("id").stringValue()));
            return ids;
        }
    }

    private Response move(String pageId, String parentId, String afterId, Account who) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", parentId);
        body.put("afterId", afterId);
        return send("POST", "/api/pages/" + pageId + "/move", body, who);
    }

    private int open(String pageId, Account who) throws Exception {
        return send("GET", "/api/pages/" + pageId, null, who).status();
    }

    @Test
    void 옮기면_새_부모의_하위에_afterId_뒤로_들어가고_자기_아래로는_옮길_수_없다() throws Exception {
        Team team = new Team();
        String a = team.page(null);
        String b = team.page(null);
        String a1 = team.page(a);
        String a2 = team.page(a);

        Response moved = move(b, a, a1, team.owner);
        assertEquals(200, moved.status());
        assertEquals(a, moved.body().get("parentId").stringValue());
        assertEquals(List.of(a1, b, a2), team.children(a, team.owner));
        assertEquals(List.of(a), team.children(null, team.owner));
        assertEquals(List.of(a), List.of(moved.body().get("path").get(0).get("id").stringValue()));

        assertEquals(400, move(a, a, null, team.owner).status(), "자기 자신 아래");
        assertEquals(400, move(a, b, null, team.owner).status(), "자기 하위 아래");
        assertEquals(400, move(b, a, b, team.owner).status(), "afterId 가 자기 자신");
        assertEquals(400, move(b, null, a1, team.owner).status(), "afterId 가 새 부모의 하위가 아님");

        // 최상위로 되돌린다.
        assertEquals(200, move(b, null, null, team.owner).status());
        assertEquals(List.of(a, b), team.children(null, team.owner));

        // 맨 앞으로 (D-50). 이미 맨 앞이어도 된다. afterId 와 함께 줄 수 없다.
        Map<String, Object> first = new HashMap<>();
        first.put("parentId", a);
        first.put("first", true);
        assertEquals(200, send("POST", "/api/pages/" + a2 + "/move", first, team.owner).status());
        assertEquals(List.of(a2, a1), team.children(a, team.owner));
        assertEquals(200, send("POST", "/api/pages/" + a2 + "/move", first, team.owner).status());
        assertEquals(List.of(a2, a1), team.children(a, team.owner));
        first.put("parentId", null);
        assertEquals(200, send("POST", "/api/pages/" + b + "/move", first, team.owner).status());
        assertEquals(List.of(b, a), team.children(null, team.owner));
        first.put("afterId", a);
        assertEquals(400, send("POST", "/api/pages/" + b + "/move", first, team.owner).status());
    }

    private List<String> shortcuts(Team team, String kind, Account who) throws Exception {
        List<String> ids = new ArrayList<>();
        send("GET", "/api/workspaces/" + team.id + "/" + kind, null, who).body()
                .forEach(page -> ids.add(page.get("id").stringValue()));
        return ids;
    }

    @Test
    void 즐겨찾기와_최근_본_페이지는_볼_수_있는_것만_보이고_최근부터_나온다() throws Exception {
        Team team = new Team();
        String a = team.page(null);
        String b = team.page(null);
        String c = team.page(null);

        assertEquals(204, send("PUT", "/api/pages/" + b + "/favorite", Map.of("favorite", true), team.member).status());
        assertEquals(204, send("PUT", "/api/pages/" + a + "/favorite", Map.of("favorite", true), team.member).status());
        assertEquals(List.of(b, a), shortcuts(team, "favorites", team.member), "즐겨찾기한 순서");
        assertTrue(send("GET", "/api/pages/" + a, null, team.member).body().get("favorite").booleanValue());
        assertEquals(List.of(), shortcuts(team, "favorites", team.owner), "사람마다 따로다");

        for (String page : List.of(a, b, c, a)) {
            assertEquals(204, send("POST", "/api/pages/" + page + "/visit", null, team.member).status());
            Thread.sleep(5);
        }
        assertEquals(List.of(a, c, b), shortcuts(team, "recent", team.member), "최근에 연 것부터, 한 번씩");

        // 제한하면 멤버에게서 빠지고, 휴지통에 보내면 모두에게서 빠진다.
        send("PUT", "/api/pages/" + a + "/restricted", Map.of("restricted", true), team.owner);
        assertEquals(List.of(b), shortcuts(team, "favorites", team.member));
        assertEquals(List.of(c, b), shortcuts(team, "recent", team.member));
        send("DELETE", "/api/pages/" + b, null, team.owner);
        assertEquals(List.of(), shortcuts(team, "favorites", team.member));

        assertEquals(204, send("PUT", "/api/pages/" + c + "/favorite", Map.of("favorite", false), team.member).status());
        assertEquals(404, send("PUT", "/api/pages/" + a + "/favorite", Map.of("favorite", true), account()).status());
        assertEquals(404, send("GET", "/api/workspaces/" + team.id + "/recent", null, account()).status());
    }

    @Test
    void 페이지는_10단계까지_중첩하고_옮겨서도_넘을_수_없다() throws Exception {
        Team team = new Team();
        String parent = null;
        List<String> chain = new ArrayList<>();
        for (int depth = 1; depth <= 10; depth++) {
            parent = team.page(parent);
            chain.add(parent);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", parent);
        assertEquals(400, send("POST", "/api/workspaces/" + team.id + "/pages", body, team.owner).status(), "11단계");

        // 두 단계짜리 가지는 9단계 아래로 옮길 수 없고(11), 8단계 아래로는 옮길 수 있다(10).
        String branch = team.page(null);
        team.page(branch);
        assertEquals(400, move(branch, chain.get(8), null, team.owner).status());
        assertEquals(200, move(branch, chain.get(7), null, team.owner).status());
    }

    @Test
    void 옮기기는_그_페이지의_FULL_과_새_부모의_EDIT_가_필요하다() throws Exception {
        Team team = new Team();
        String a = team.page(null);
        String locked = team.page(null);
        send("PUT", "/api/pages/" + locked + "/restricted", Map.of("restricted", true), team.owner);
        Account stranger = account();

        // MEMBER 의 기본 권한은 EDIT 라 옮길 수 없다.
        assertEquals(403, move(a, null, null, team.member).status());
        assertEquals(404, move(a, null, null, stranger).status());
        // FULL 을 받아도 볼 수 없는 부모 아래로는 옮길 수 없다.
        send("PUT", "/api/pages/" + a + "/grants/" + team.member.id(), Map.of("level", "FULL"), team.owner);
        assertEquals(404, move(a, locked, null, team.member).status());
        send("PUT", "/api/pages/" + locked + "/grants/" + team.member.id(), Map.of("level", "VIEW"), team.owner);
        assertEquals(403, move(a, locked, null, team.member).status());
    }

    @Test
    void 제한된_페이지_아래로_옮기면_물려받는_권한이_바뀌고_열린_연결이_다시_계산하게_알린다() throws Exception {
        Team team = new Team();
        String locked = team.page(null);
        send("PUT", "/api/pages/" + locked + "/restricted", Map.of("restricted", true), team.owner);
        String b = team.page(null);
        String b1 = team.page(b);
        assertEquals(200, open(b1, team.member));

        Changes.SEEN.clear();
        assertEquals(200, move(b, locked, null, team.owner).status());

        assertEquals(403, open(b1, team.member), "제한된 부모 아래에서는 MEMBER 의 기본 권한이 없다");
        PermissionChange change = Changes.SEEN.get(Changes.SEEN.size() - 1);
        assertTrue(change.affects(UUID.fromString(b), team.member.id()));
        assertTrue(change.affects(UUID.fromString(b1), team.member.id()));
        assertFalse(change.affects(UUID.fromString(locked), team.member.id()));
    }

    @Test
    void 휴지통으로_보내면_하위까지_보이지_않고_꺼내면_돌아온다() throws Exception {
        Team team = new Team();
        String a = team.page(null);
        String a1 = team.page(a);
        String b = team.page(null);

        Changes.SEEN.clear();
        assertEquals(403, send("DELETE", "/api/pages/" + a, null, team.member).status(), "FULL 이 필요하다");
        assertEquals(204, send("DELETE", "/api/pages/" + a, null, team.owner).status());
        assertTrue(Changes.SEEN.get(Changes.SEEN.size() - 1).affects(UUID.fromString(a1), team.member.id()));

        assertEquals(List.of(b), team.children(null, team.owner));
        assertEquals(404, open(a, team.owner));
        assertEquals(404, open(a1, team.owner));
        assertEquals(404, send("POST", "/api/docs/" + a1 + "/ws-ticket", null, team.owner).status());
        assertEquals(404, send("DELETE", "/api/pages/" + a, null, team.owner).status(), "이미 휴지통에 있다");
        // 휴지통에는 보낸 페이지 자신만 있다. 하위는 부모를 따라 숨었을 뿐이다.
        assertEquals(List.of(a), team.trash(team.owner));
        JsonNode memberView = send("GET", "/api/workspaces/" + team.id + "/trash", null, team.member).body().get(0);
        assertFalse(memberView.get("canRestore").booleanValue(), "MEMBER 의 기본 권한은 EDIT 다");

        assertEquals(403, send("POST", "/api/pages/" + a + "/restore", null, team.member).status());
        Response restored = send("POST", "/api/pages/" + a + "/restore", null, team.owner);
        assertEquals(200, restored.status());
        assertEquals(List.of(a, b), team.children(null, team.owner));
        assertEquals(List.of(a1), team.children(a, team.owner));
        assertEquals(List.of(), team.trash(team.owner));
        assertEquals(404, send("POST", "/api/pages/" + a + "/restore", null, team.owner).status(), "휴지통에 없다");
    }

    @Test
    void 부모가_아직_휴지통에_있으면_꺼낸_페이지는_최상위로_간다() throws Exception {
        Team team = new Team();
        String parent = team.page(null);
        String child = team.page(parent);
        send("DELETE", "/api/pages/" + child, null, team.owner);
        send("DELETE", "/api/pages/" + parent, null, team.owner);
        assertEquals(List.of(parent, child), team.trash(team.owner));

        Response restored = send("POST", "/api/pages/" + child + "/restore", null, team.owner);

        assertEquals(200, restored.status());
        assertTrue(restored.body().get("parentId").isNull());
        assertEquals(List.of(child), team.children(null, team.owner));
        assertEquals(List.of(parent), team.trash(team.owner));
    }

    @Test
    void 휴지통_목록은_보내기_전에_볼_수_있던_페이지만_보인다() throws Exception {
        Team team = new Team();
        String secret = team.page(null);
        send("PUT", "/api/pages/" + secret + "/restricted", Map.of("restricted", true), team.owner);
        String open = team.page(null);
        send("DELETE", "/api/pages/" + secret, null, team.owner);
        send("DELETE", "/api/pages/" + open, null, team.owner);
        Account stranger = account();

        assertEquals(List.of(open, secret), team.trash(team.owner));
        assertEquals(List.of(open), team.trash(team.member), "제한된 페이지의 제목이 새지 않는다");
        assertEquals(404, send("GET", "/api/workspaces/" + team.id + "/trash", null, stranger).status());
        // 워크스페이스 멤버가 볼 수 없는 페이지는 GET 과 같이 403 이다(ID 를 아는 경우에만, 제목은 드러나지 않는다).
        assertEquals(403, send("POST", "/api/pages/" + secret + "/restore", null, team.member).status());
        assertEquals(404, send("POST", "/api/pages/" + secret + "/restore", null, stranger).status());
    }

    @Test
    void 두_사람이_동시에_서로의_아래로_옮겨도_고리가_생기지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            Team team = new Team();
            String a = team.page(null);
            String b = team.page(null);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = pool.submit(() -> {
                    start.await();
                    return move(a, b, null, team.owner).status();
                });
                Future<Integer> second = pool.submit(() -> {
                    start.await();
                    return move(b, a, null, team.owner).status();
                });
                start.countDown();
                List<Integer> statuses = List.of(first.get(), second.get());

                assertTrue(statuses.contains(200) && statuses.contains(400), "하나만 옮겨진다: " + statuses);
                assertEquals(1, team.children(null, team.owner).size(), "최상위에 하나가 남는다(고리라면 0)");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
