package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 지표가 관리 포트로만 나가고, 편집하면 협업 지표가 쌓이며, 문서와 사용자 ID 는 싣지 않는다 (진단 OPS-1, 13.4). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=0")
class MetricsTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int on, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + on + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

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

    private static double value(String body, String metric) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(metric) + "(\\{[^}]*\\})? ([0-9.eE+-]+)$").matcher(body);
        assertTrue(matcher.find(), metric + " 가 없다");
        return Double.parseDouble(matcher.group(2));
    }

    @Test
    void 편집하면_협업_지표가_관리_포트에_쌓이고_API_포트로는_열리지_않는다() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        send("POST", "/api/auth/signup", Map.of("email", email, "password", "correct horse 1", "name", "a"), null);
        String token = send("POST", "/api/auth/login", Map.of("email", email, "password", "correct horse 1"), null)
                .get("accessToken").stringValue();
        String workspace = send("POST", "/api/workspaces", Map.of("name", "팀"), token).get("id").stringValue();
        String page = send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), token).get("id").stringValue();
        String ticket = send("POST", "/api/docs/" + page + "/ws-ticket", null, token).get("ticket").stringValue();
        double acksBefore = value(get(managementPort, "/actuator/prometheus").body(), "collab_ack_latency_seconds_count");
        TestClient client = new TestClient("tab");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
        client.process(5_000);
        for (int i = 0; i < 3; i++) {
            client.edit(List.of(new TextOp(page, Delta.builder().insert("가").build())));
            while (client.session.hasPending() && client.process(5_000)) {
                // ack 를 기다린다.
            }
        }

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode());
        String body = metrics.body();
        assertEquals(acksBefore + 3, value(body, "collab_ack_latency_seconds_count"));
        assertTrue(value(body, "collab_sessions") >= 1);
        assertTrue(value(body, "collab_connections") >= 1);
        assertTrue(value(body, "collab_session_load_seconds_count") >= 1);
        assertTrue(value(body, "collab_op_bytes_count") >= 3);
        assertTrue(body.contains("collab_ack_latency_seconds_bucket{le="), "p99 를 계산할 히스토그램을 낸다");
        assertTrue(body.contains("collab_transform_count_bucket{le="));
        assertTrue(body.contains("http_server_requests_seconds"), "HTTP 지표도 낸다");
        // 레이블에 문서와 사용자를 싣지 않는다.
        assertFalse(body.contains(page), "문서 ID 가 지표에 있다");
        assertFalse(body.contains(email), "이메일이 지표에 있다");
        assertEquals(200, get(managementPort, "/actuator/health").statusCode());

        assertTrue(get(port, "/actuator/prometheus").statusCode() >= 400, "API 포트로는 열리지 않는다");
    }
}
