package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.RichText;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 에 닿지 못할 때의 정책 (docs/07-SECURITY.md 9.7). Redis 가 필요한 요청은 503 으로 거절하고(막힘), 이미 열린 편집 연결은
 * 계속된다. Redis 가 돌아오면 그대로 다시 된다.
 *
 * <p>다른 테스트와 함께 쓰는 Redis 를 멈출 수 없으므로 이 테스트만의 Redis 컨테이너를 띄워 일시 정지한다. 일시 정지한 Redis 는
 * 연결은 받되 답하지 않으므로, 실제 장애에서 가장 나쁜 경우(응답 없음)를 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisOutageTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";
    private static final GenericContainer<?> REDIS = startRedis();

    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7").withExposedPorts(6379);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestInfra.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("management.server.port", () -> "0");
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private boolean paused;

    private record Response(int status, JsonNode body, List<String> setCookies, String retryAfter) {
        String refreshCookie() {
            return setCookies.stream()
                    .filter(value -> value.startsWith("copage_refresh="))
                    .map(value -> value.substring("copage_refresh=".length(), value.indexOf(';')))
                    .findFirst()
                    .orElse(null);
        }
    }

    @AfterEach
    void resume() {
        if (paused) {
            unpause();
        }
    }

    private void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        paused = true;
    }

    private void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        paused = false;
    }

    private Response send(String method, String path, Object json, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(json)));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        JsonNode body = isJson && !response.body().isEmpty() ? MAPPER.readTree(response.body()) : null;
        return new Response(response.statusCode(), body, response.headers().allValues("Set-Cookie"),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    private Response login(String email) throws Exception {
        return send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD));
    }

    private static void assertUnavailable(Response response) {
        assertEquals(503, response.status(), String.valueOf(response.body()));
        assertEquals(503, response.body().get("status").intValue());
        assertTrue(response.retryAfter() != null, "Retry-After 를 준다");
    }

    @Test
    void Redis_에_닿지_못하면_Redis_가_필요한_요청은_503_이고_열린_편집_연결은_계속된다() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"))
                .status());
        // 운영과 같게 재발급 스크립트를 Redis 에 먼저 올려 둔다. 올라가 있지 않으면 정지 중 밀린 재발급이 되살린 뒤 NOSCRIPT 로
        // 끝나 아무것도 바꾸지 않으므로, 밀린 명령이 나중에 실행되는 경우를 시험하지 못한다.
        Response first = login(email);
        Response session = send("POST", "/api/auth/refresh", null, "Cookie", "copage_refresh=" + first.refreshCookie());
        assertEquals(200, session.status());
        String bearer = "Bearer " + session.body().get("accessToken").stringValue();
        String workspaceId = send("POST", "/api/workspaces", Map.of("name", "팀"), "Authorization", bearer)
                .body().get("id").stringValue();
        UUID docId = UUID.fromString(send("POST", "/api/workspaces/" + workspaceId + "/pages", Map.of(),
                "Authorization", bearer).body().get("id").stringValue());
        String ticket = send("POST", "/api/docs/" + docId + "/ws-ticket", null, "Authorization", bearer)
                .body().get("ticket").stringValue();
        String spareTicket = send("POST", "/api/docs/" + docId + "/ws-ticket", null, "Authorization", bearer)
                .body().get("ticket").stringValue();
        TestClient client = new TestClient("tab");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=1"));
        assertTrue(client.process(5_000));

        pause();

        // Access 토큰 검사는 차단 목록을 Redis 에서 읽는다. 401 이 아니라 503 이라 클라이언트는 로그아웃하지 않는다.
        assertUnavailable(send("GET", "/api/me", null, "Authorization", bearer));
        assertUnavailable(send("POST", "/api/docs/" + docId + "/ws-ticket", null, "Authorization", bearer));
        assertUnavailable(login(email));
        Response refresh = send("POST", "/api/auth/refresh", null, "Cookie", "copage_refresh=" + session.refreshCookie());
        assertUnavailable(refresh);
        assertNull(refresh.refreshCookie(), "Refresh 쿠키를 지우지 않는다");
        assertUnavailable(send("POST", "/api/auth/logout", null,
                "Cookie", "copage_refresh=" + session.refreshCookie(), "Authorization", bearer));
        // 가입은 PostgreSQL 만 쓴다.
        assertEquals(201, send("POST", "/api/auth/signup",
                Map.of("email", "u-" + UUID.randomUUID() + "@example.com", "password", PASSWORD, "name", "b")).status());
        // 티켓을 쓰는 핸드셰이크도 열지 않는다.
        TestClient late = new TestClient("late");
        assertThrows(Exception.class, () -> late.connect(
                URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + spareTicket + "&v=1")));

        // 생존 검사(liveness)는 Redis 를 보지 않아 재시작을 부르지 않는다. 묶은 health 는 Redis 를 보므로 생존 검사에 쓰면 안 된다(D-25).
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health/liveness"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(503, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        // 열린 편집 연결은 Redis 를 쓰지 않는다.
        client.edit(List.of(new InsertBlock("X", docId.toString(), "a", "paragraph", Attrs.NONE, RichText.of("가"))));
        while (client.session.hasPending() && client.process(5_000)) {
            // ack 를 기다린다.
        }
        assertFalse(client.session.hasPending(), "Redis 가 멈춰도 편집은 확정된다");
        assertEquals(1, client.session.confirmedRev());

        unpause();

        assertEquals(200, send("GET", "/api/me", null, "Authorization", bearer).status());
        // 정지 중 503 을 받은 재발급은 되살린 뒤에도 토큰을 쓰지 않는다. 같은 쿠키로 다시 받을 수 있다. 재발급은 주인을 먼저
        // 읽으므로(owner) 완전히 멈춘 동안에는 회전 스크립트를 보내지도 않는다. 두 호출 사이에 Redis 가 멈추는 경우는 회전
        // 스크립트의 기한이 막고, RedisRefreshTokenStoreTest 가 확인한다.
        Response again = send("POST", "/api/auth/refresh", null, "Cookie", "copage_refresh=" + session.refreshCookie());
        assertEquals(200, again.status(), String.valueOf(again.body()));
        // 유예 시간이 지난 뒤에도 재사용으로 몰리지 않는다. 새 쿠키로 계속 쓸 수 있다.
        assertEquals(200, send("POST", "/api/auth/refresh", null, "Cookie", "copage_refresh=" + again.refreshCookie())
                .status());
    }
}
