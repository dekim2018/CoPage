package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 댓글, 멘션과 알림, 실시간 알림 연결, 사이드바 트리 이벤트 (C-1~C-4, W-5, D-46) 를 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommentFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

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
        return new Response(response.statusCode(), response.body().isEmpty() ? null : MAPPER.readTree(response.body()));
    }

    private Account account(String name) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", name), null)
                .body();
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null).body();
        return new Account(UUID.fromString(user.get("id").stringValue()), email, login.get("accessToken").stringValue());
    }

    private final class Team {
        final Account owner = account("주인");
        final Account member = account("멤버");
        final Account viewer = account("보기만");
        final String id;
        final String page;

        Team() throws Exception {
            id = send("POST", "/api/workspaces", Map.of("name", "팀"), owner).body().get("id").stringValue();
            send("POST", "/api/workspaces/" + id + "/members", Map.of("email", member.email(), "role", "MEMBER"), owner);
            page = send("POST", "/api/workspaces/" + id + "/pages", Map.of(), owner).body().get("id").stringValue();
            send("PUT", "/api/pages/" + page + "/grants/" + viewer.id(), Map.of("level", "VIEW"), owner);
        }
    }

    private Response comment(String page, String quote, String body, List<UUID> mentions, Account who) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("quote", quote);
        request.put("body", body);
        request.put("mentions", mentions);
        return send("POST", "/api/docs/" + page + "/comments", request, who);
    }

    private long unread(Account who) throws Exception {
        return send("GET", "/api/notifications", null, who).body().get("unread").longValue();
    }

    /** 알림 연결(SSE)을 열고 받은 이벤트 이름을 큐에 넣는다. */
    private BlockingQueue<String> stream(Account who) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notifications/stream"))
                .header("Authorization", "Bearer " + who.token())
                .GET()
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenAccept(response -> response.body()
                .filter(line -> line.startsWith("event:"))
                .forEach(line -> events.add(line.substring("event:".length()).trim())));
        assertEquals("ready", events.poll(5, TimeUnit.SECONDS));
        return events;
    }

    private static String awaitEvent(BlockingQueue<String> events, String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String event = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (name.equals(event)) {
                return event;
            }
        }
        throw new AssertionError(name + " 이벤트를 받지 못했다");
    }

    @Test
    void 댓글은_COMMENT_부터_쓰고_답글과_멘션은_알림으로_가며_해결하고_다시_연다() throws Exception {
        Team team = new Team();
        Account stranger = account("남");

        assertEquals(403, comment(team.page, "원문", "읽기만 하는 사람", List.of(), team.viewer).status());
        List<UUID> crowd = Stream.generate(UUID::randomUUID).limit(51).toList();
        assertEquals(400, comment(team.page, "원문", "너무 많은 멘션", crowd, team.member).status());
        Response created = comment(team.page, "원문", "확인 부탁", List.of(team.owner.id(), stranger.id()), team.member);
        assertEquals(201, created.status());
        String thread = created.body().get("id").stringValue();
        assertEquals(1, unread(team.owner), "멘션된 주인");
        assertEquals(0, unread(stranger), "페이지를 볼 수 없는 사람은 멘션해도 알리지 않는다");

        assertEquals(201, send("POST", "/api/comments/" + thread + "/replies", Map.of("body", "봤다"), team.owner).status());
        assertEquals(1, unread(team.member), "스레드에 쓴 사람에게 답글 알림");
        JsonNode inbox = send("GET", "/api/notifications", null, team.member).body();
        assertEquals("REPLY", inbox.get("items").get(0).get("kind").stringValue());
        assertEquals("주인", inbox.get("items").get(0).get("actorName").stringValue());
        send("POST", "/api/notifications/read", Map.of("ids", List.of()), team.member);
        assertEquals(0, unread(team.member));

        JsonNode threads = send("GET", "/api/docs/" + team.page + "/comments", null, team.viewer).body();
        assertEquals(1, threads.size());
        assertEquals(2, threads.get(0).get("comments").size());
        assertEquals("원문", threads.get(0).get("quote").stringValue());

        assertEquals(204, send("PUT", "/api/comments/" + thread + "/resolved", Map.of("resolved", true), team.member).status());
        assertTrue(send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body().get(0).get("resolved")
                .booleanValue());
        send("PUT", "/api/comments/" + thread + "/resolved", Map.of("resolved", false), team.member);
        assertEquals(false, send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body().get(0)
                .get("resolved").booleanValue());
        assertEquals(403, send("PUT", "/api/comments/" + thread + "/resolved", Map.of("resolved", true), team.viewer)
                .status());
        assertEquals(404, send("GET", "/api/docs/" + team.page + "/comments", null, stranger).status());
    }

    @Test
    void 댓글이_바뀌면_문서를_연_연결과_알림_연결로_바로_알린다() throws Exception {
        Team team = new Team();
        String ticket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, team.viewer).body().get("ticket")
                .stringValue();
        RawClient viewer = new RawClient(false);
        viewer.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket=" + ticket + "&v=1"),
                "viewer");
        BlockingQueue<String> ownerEvents = stream(team.owner);

        comment(team.page, "원문", "@주인 봐 주세요", List.of(team.owner.id()), team.member);

        assertTrue(viewer.await("comment", 3_000).message().has("threadId"));
        awaitEvent(ownerEvents, "notification");
        // 다른 사람이 페이지를 만들면 멤버의 사이드바에 tree 이벤트가 간다.
        BlockingQueue<String> memberEvents = stream(team.member);
        send("POST", "/api/workspaces/" + team.id + "/pages", Map.of(), team.owner);
        awaitEvent(memberEvents, "tree");
        // 제목이 바뀌어도 사이드바가 다시 읽도록 알린다.
        String ownerTicket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, team.owner).body()
                .get("ticket").stringValue();
        TestClient writer = new TestClient("owner");
        writer.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket=" + ownerTicket + "&v=1"));
        while (!writer.connected()) {
            writer.process(5_000);
        }
        writer.edit(List.of(new TextOp(team.page, Delta.builder().insert("회의록").build())));
        while (writer.session.hasPending()) {
            writer.process(5_000);
        }
        awaitEvent(memberEvents, "tree");
    }

    @Test
    void 본문_멘션은_한_번만_알리고_볼_수_없는_사람에게는_알리지_않는다() throws Exception {
        Team team = new Team();
        Account stranger = account("남");
        String ticket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, team.member).body().get("ticket")
                .stringValue();
        TestClient client = new TestClient("writer");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket=" + ticket + "&v=1"));
        while (!client.connected()) {
            client.process(5_000);
        }

        String mention = "m1:" + team.owner.id();
        client.edit(List.of(new TextOp(team.page, Delta.builder().insert("@주인", Attrs.of("mention", mention)).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
        // 같은 멘션이 다시 들어와도(되돌리기 뒤 다시 하기 등) 알림은 한 번이다.
        client.edit(List.of(new TextOp(team.page, Delta.builder().insert("@주인", Attrs.of("mention", mention)).build())));
        client.edit(List.of(new TextOp(team.page,
                Delta.builder().insert("@남", Attrs.of("mention", "m2:" + stranger.id())).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }

        long deadline = System.currentTimeMillis() + 5_000;
        while (unread(team.owner) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertEquals(1, unread(team.owner));
        JsonNode item = send("GET", "/api/notifications", null, team.owner).body().get("items").get(0);
        assertEquals("MENTION", item.get("kind").stringValue());
        assertEquals(team.page, item.get("docId").stringValue());
        assertEquals(0, unread(stranger));
    }
}
