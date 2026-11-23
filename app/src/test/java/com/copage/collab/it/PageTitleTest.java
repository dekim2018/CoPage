package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 제목 줄(루트 블록)을 고치면 페이지 목록과 페이지 조회의 제목이 바뀐다 (D-21, #26). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageTitleTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode send(String method, String path, Object json, String token) throws Exception {
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
        return response.body().isEmpty() ? null : MAPPER.readTree(response.body());
    }

    @Test
    void 제목_줄을_고치면_목록의_제목이_바뀐다() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        send("POST", "/api/auth/signup", Map.of("email", email, "password", "correct horse 1", "name", "a"), null);
        String token = send("POST", "/api/auth/login", Map.of("email", email, "password", "correct horse 1"), null)
                .get("accessToken").stringValue();
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), token).get("id").stringValue();
        String page = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), token).get("id").stringValue();
        String ticket = send("POST", "/api/docs/" + page + "/ws-ticket", null, token).get("ticket").stringValue();
        TestClient client = new TestClient("tab");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
        client.process(5_000);

        client.edit(List.of(new TextOp(page, Delta.builder().insert("  주간 회의록  ").build())));
        while (client.session.hasPending() && client.process(5_000)) {
            // ack 를 기다린다.
        }

        String title = null;
        for (int i = 0; i < 100 && !"주간 회의록".equals(title); i++) {
            title = send("GET", "/api/workspaces/" + workspace + "/pages", null, token).get(0).get("title").stringValue();
            Thread.sleep(20);
        }
        assertEquals("주간 회의록", title, "앞뒤 공백은 뺀다");
        assertEquals("주간 회의록", send("GET", "/api/pages/" + page, null, token).get("title").stringValue());
    }
}
