package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.comment.app.CommentService;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
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
            // 권한을 받은 알림(PAGE_SHARED)은 따로 본다. 다른 시험의 숫자에 섞이지 않게 읽어 둔다.
            send("POST", "/api/notifications/read", Map.of("ids", List.of()), viewer);
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

    @Test
    void 쓴_사람만_고치고_쓴_사람이나_FULL_이_지우며_지운_댓글의_알림도_지운다() throws Exception {
        Team team = new Team();
        Response created = comment(team.page, "원문", "처음 글", List.of(), team.member);
        String thread = created.body().get("id").stringValue();
        String first = created.body().get("comments").get(0).get("id").stringValue();
        String reply = send("POST", "/api/comments/" + thread + "/replies",
                Map.of("body", "@주인 답글", "mentions", List.of(team.owner.id())), team.member).body().get("id")
                .stringValue();
        assertEquals(1, unread(team.owner));

        // 고치기: 쓴 사람만. 새로 멘션한 사람에게 알린다.
        assertEquals(403, send("PATCH", "/api/comments/" + first, Map.of("body", "남이 고침"), team.owner).status());
        Response edited = send("PATCH", "/api/comments/" + first,
                Map.of("body", "고친 글 @보기만", "mentions", List.of(team.viewer.id())), team.member);
        assertEquals(200, edited.status());
        assertTrue(edited.body().get("editedAt").isString());
        assertEquals(1, unread(team.viewer), "고치며 새로 멘션한 사람(볼 수 있음)에게 알린다");
        send("PATCH", "/api/comments/" + first,
                Map.of("body", "고친 글 @보기만", "mentions", List.of(team.viewer.id())), team.member);
        assertEquals(1, unread(team.viewer), "같은 댓글에서 같은 사람은 한 번만");
        assertEquals(400, send("PATCH", "/api/comments/" + first, Map.of("body", " "), team.member).status());

        JsonNode comments = send("GET", "/api/docs/" + team.page + "/comments", null, team.viewer).body().get(0)
                .get("comments");
        assertEquals("고친 글 @보기만", comments.get(0).get("body").stringValue());

        // 지우기: 보기만 하는 사람은 못 지우고, 쓴 사람은 지운다. 그 댓글이 보낸 멘션 알림도 사라진다.
        assertEquals(403, send("DELETE", "/api/comments/" + reply, null, team.viewer).status());
        Response deleted = send("DELETE", "/api/comments/" + reply, null, team.member);
        assertEquals(200, deleted.status());
        assertEquals(false, deleted.body().get("threadDeleted").booleanValue());
        assertEquals(0, unread(team.owner), "지운 답글의 멘션 알림");
        JsonNode after = send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body().get(0)
                .get("comments");
        assertTrue(after.get(1).get("deleted").booleanValue());
        assertEquals("", after.get(1).get("body").stringValue());
        assertEquals(404, send("PATCH", "/api/comments/" + reply, Map.of("body", "되살리기"), team.member).status());
        assertEquals(404, send("DELETE", "/api/comments/" + reply, null, team.member).status());

        // FULL(주인)은 남의 댓글도 지운다. 남은 댓글이 없으면 스레드도 사라진다.
        Response last = send("DELETE", "/api/comments/" + first, null, team.owner);
        assertEquals(true, last.body().get("threadDeleted").booleanValue());
        assertEquals(0, send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body().size());
    }

    @Autowired
    CommentService commentService;

    @Autowired
    JdbcClient jdbc;

    private boolean orphaned(Team team, String thread) throws Exception {
        for (JsonNode node : send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body()) {
            if (node.get("id").stringValue().equals(thread)) {
                return node.get("orphaned").booleanValue();
            }
        }
        throw new AssertionError("스레드가 없다: " + thread);
    }

    @Test
    void 원문이_다_지워지면_원문이_삭제됨으로_표시하고_서식이_한_번도_붙지_않은_스레드는_정리한다() throws Exception {
        Team team = new Team();
        String ticket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, team.member).body().get("ticket")
                .stringValue();
        TestClient client = new TestClient("writer");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket=" + ticket + "&v=1"));
        while (!client.connected()) {
            client.process(5_000);
        }
        String thread = comment(team.page, "검토", "봐 주세요", List.of(), team.member).body().get("id").stringValue();
        client.edit(List.of(new TextOp(team.page, Delta.builder().insert("검토", Attrs.of("comment", thread)).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
        // 세션이 몇 초 안에 본문의 서식을 알린다. 아직 지우지 않았으니 표시가 없다.
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.sql("SELECT anchored_at IS NOT NULL FROM comment_threads WHERE id = :id")
                .param("id", UUID.fromString(thread)).query(Boolean.class).single() == Boolean.FALSE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(false, orphaned(team, thread));

        client.edit(List.of(new TextOp(team.page, Delta.builder().delete(2).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
        deadline = System.currentTimeMillis() + 10_000;
        while (!orphaned(team, thread) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(orphaned(team, thread), "원문이 지워졌다");

        // 다시 넣으면(되돌리기) 표시가 꺼진다.
        client.edit(List.of(new TextOp(team.page, Delta.builder().insert("검토", Attrs.of("comment", thread)).build())));
        while (client.session.hasPending()) {
            client.process(5_000);
        }
        deadline = System.currentTimeMillis() + 10_000;
        while (orphaned(team, thread) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(false, orphaned(team, thread));

        // 서식이 한 번도 붙지 않은 오래된 스레드만 지운다. 원문이 지워진 스레드는 남는다.
        String never = comment(team.page, "없는 글", "서식이 실패했다", List.of(), team.member).body().get("id")
                .stringValue();
        jdbc.sql("UPDATE comment_threads SET created_at = now() - interval '2 days' WHERE id IN (:ids)")
                .param("ids", List.of(UUID.fromString(never), UUID.fromString(thread)))
                .update();
        assertTrue(commentService.purgeUnanchored(Duration.ofDays(1)) >= 1);
        JsonNode left = send("GET", "/api/docs/" + team.page + "/comments", null, team.owner).body();
        assertEquals(1, left.size());
        assertEquals(thread, left.get(0).get("id").stringValue());
    }

    @Test
    void 페이지_권한을_받으면_알림이_가고_같은_등급은_한_번만_간다() throws Exception {
        Team team = new Team();
        Account guest = account("손님");
        send("PUT", "/api/pages/" + team.page + "/grants/" + guest.id(), Map.of("level", "COMMENT"), team.owner);
        send("PUT", "/api/pages/" + team.page + "/grants/" + guest.id(), Map.of("level", "COMMENT"), team.owner);
        JsonNode inbox = send("GET", "/api/notifications", null, guest).body();
        assertEquals(1, inbox.get("unread").longValue());
        JsonNode item = inbox.get("items").get(0);
        assertEquals("PAGE_SHARED", item.get("kind").stringValue());
        assertEquals(team.page, item.get("docId").stringValue());
        assertEquals("주인", item.get("actorName").stringValue());
        assertEquals("댓글 권한을 받았다", item.get("preview").stringValue());

        send("PUT", "/api/pages/" + team.page + "/grants/" + guest.id(), Map.of("level", "EDIT"), team.owner);
        assertEquals(2, unread(guest), "등급이 바뀌면 다시 알린다");
    }

    /** 페이지에 COMMENT 만 받은 사람을 만든다. 받은 알림은 읽어 둔다. */
    private Account commenter(Team team) throws Exception {
        Account commenter = account("댓글만");
        assertEquals(200, send("PUT", "/api/pages/" + team.page + "/grants/" + commenter.id(), Map.of("level", "COMMENT"),
                team.owner).status());
        send("POST", "/api/notifications/read", Map.of("ids", List.of()), commenter);
        return commenter;
    }

    @Test
    void 댓글_권한으로_댓글과_답글을_달고_해결하지만_남의_댓글은_지우지_못한다() throws Exception {
        Team team = new Team();
        Account commenter = commenter(team);

        Response created = comment(team.page, "원문", "여기 확인", List.of(team.owner.id()), commenter);
        assertEquals(201, created.status());
        String thread = created.body().get("id").stringValue();
        assertEquals(1, unread(team.owner), "댓글 권한으로 단 멘션도 알린다");
        assertEquals(201, send("POST", "/api/comments/" + thread + "/replies", Map.of("body", "덧붙임"), commenter)
                .status());
        assertEquals(204, send("PUT", "/api/comments/" + thread + "/resolved", Map.of("resolved", true), commenter)
                .status());
        send("PUT", "/api/comments/" + thread + "/resolved", Map.of("resolved", false), commenter);

        String others = send("POST", "/api/comments/" + thread + "/replies", Map.of("body", "주인 답"), team.owner).body()
                .get("id").stringValue();
        assertEquals(403, send("DELETE", "/api/comments/" + others, null, commenter).status(), "지우기는 쓴 사람이나 FULL");
        assertEquals(403, send("PATCH", "/api/comments/" + others, Map.of("body", "고침"), commenter).status());
        assertEquals(3, send("GET", "/api/docs/" + team.page + "/comments", null, commenter).body().get(0)
                .get("comments").size());
    }

    @Test
    void 댓글_권한의_연결은_comment_서식만_확정되고_글자를_넣거나_지우면_nack_를_받는다() throws Exception {
        Team team = new Team();
        Account commenter = commenter(team);
        String ownerTicket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, team.owner).body()
                .get("ticket").stringValue();
        TestClient writer = new TestClient("owner");
        writer.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket=" + ownerTicket + "&v=1"));
        while (!writer.connected()) {
            writer.process(5_000);
        }
        writer.edit(List.of(new TextOp(team.page, Delta.builder().insert("검토할 문장").build())));
        while (writer.session.hasPending()) {
            writer.process(5_000);
        }

        JsonNode ticket = send("POST", "/api/docs/" + team.page + "/ws-ticket", null, commenter).body();
        assertEquals("COMMENT", ticket.get("role").stringValue());
        RawClient client = new RawClient(false);
        JsonNode welcome = client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + team.page + "?ticket="
                + ticket.get("ticket").stringValue() + "&v=1"), "commenter");
        assertEquals("COMMENT", welcome.get("role").stringValue());
        long rev = welcome.get("rev").longValue();

        client.send(change(1, rev, team.page, "{\"insert\":\"끼어듦\"}"));
        JsonNode inserted = client.await("nack", 3_000).message();
        assertEquals(1, inserted.get("clientSeq").longValue());
        assertEquals("permission", inserted.get("code").stringValue());
        assertEquals(rev, inserted.get("rev").longValue(), "거부한 묶음은 리비전을 올리지 않는다");

        client.send(change(2, rev, team.page, "{\"delete\":2}"));
        assertEquals("permission", client.await("nack", 3_000).message().get("code").stringValue());

        String thread = comment(team.page, "검토", "이 단어", List.of(), commenter).body().get("id").stringValue();
        client.send(change(3, rev, team.page, "{\"retain\":2,\"attrs\":{\"comment\":\"" + thread + "\"}}"));
        JsonNode ack = client.await("ack", 3_000).message();
        assertEquals(3, ack.get("clientSeq").longValue());
        assertEquals(rev + 1, ack.get("rev").longValue());

        // 확정된 서식은 본문에 남아, 세션이 스레드에 위치가 붙었다고 적는다.
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.sql("SELECT anchored_at IS NOT NULL FROM comment_threads WHERE id = :id")
                .param("id", UUID.fromString(thread)).query(Boolean.class).single() == Boolean.FALSE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(Boolean.TRUE, jdbc.sql("SELECT anchored_at IS NOT NULL FROM comment_threads WHERE id = :id")
                .param("id", UUID.fromString(thread)).query(Boolean.class).single());
    }

    private static String change(int clientSeq, long baseRev, String blockId, String delta) {
        return "{\"t\":\"change\",\"clientSeq\":" + clientSeq + ",\"baseRev\":" + baseRev
                + ",\"ops\":[{\"op\":\"text\",\"blockId\":\"" + blockId + "\",\"delta\":[" + delta + "]}]}";
    }
}
