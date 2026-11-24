package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.CloseCodes;
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
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 이 답하지 않을 때 (docs/11-TEST-PLAN.md 13.2, 진단 4단계). 편집 중인 문서의 커밋이 정해진 시간 안에 실패해 세션을
 * 버리고(4009), 그동안 API 는 503 이며, DB 가 돌아오면 클라이언트가 재접속해 보내지 못한 편집을 다시 보내 한 번만 확정된다.
 *
 * <p>다른 시험과 함께 쓰는 DB 를 멈출 수 없으므로 이 시험만의 PostgreSQL 을 띄워 일시 정지한다. 일시 정지한 DB 는 연결을 받되
 * 답하지 않아 가장 나쁜 경우(응답 없음)를 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresOutageTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";
    private static final PostgreSQLContainer POSTGRES = startPostgres();

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestInfra.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "2");
    }

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private boolean paused;

    @AfterEach
    void resume() {
        if (paused) {
            unpause();
        }
    }

    private void pause() {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        paused = true;
    }

    private void unpause() {
        POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        paused = false;
    }

    private HttpResponse<String> send(String method, String path, Object json, String token) throws Exception {
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
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return MAPPER.readTree(response.body());
    }

    private void connect(TestClient client, String page, String token) throws Exception {
        String ticket = json(send("POST", "/api/docs/" + page + "/ws-ticket", null, token)).get("ticket").stringValue();
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + page + "?ticket=" + ticket + "&v=1"));
    }

    private static void type(TestClient client, String page, String text) throws Exception {
        client.edit(List.of(new TextOp(page, Delta.builder().retain(client.session.view().get(page).text().length())
                .insert(text).build())));
    }

    private static int awaitClose(TestClient client, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            client.process(50);
            synchronized (client.closes) {
                if (!client.closes.isEmpty()) {
                    return client.closes.get(client.closes.size() - 1).code();
                }
            }
        }
        throw new AssertionError("닫히지 않았다");
    }

    @Test
    void 마이그레이션은_소켓_시간_한도가_걸린_앱의_연결_풀을_쓰지_않는다() {
        assertNotSame(dataSource, flyway.getConfiguration().getDataSource());
    }

    @Test
    void DB_가_답하지_않으면_커밋이_시간_안에_실패해_세션을_버리고_돌아오면_보내지_못한_편집이_한_번만_확정된다() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "a"), null);
        String token = json(send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null))
                .get("accessToken").stringValue();
        String workspace = json(send("POST", "/api/workspaces", Map.of("name", "팀"), token)).get("id").stringValue();
        String page = json(send("POST", "/api/workspaces/" + workspace + "/pages", Map.of(), token)).get("id").stringValue();
        TestClient client = new TestClient("tab");
        connect(client, page, token);
        client.process(5_000);
        type(client, page, "가");
        while (client.session.hasPending() && client.process(5_000)) {
            // ack 를 기다린다.
        }

        pause();
        long pausedAt = System.currentTimeMillis();
        type(client, page, "나");
        // 커밋이 소켓 시간 한도(2초) 안에 실패하고 세션을 버린다. 실행 흐름이 영영 막히지 않는다.
        assertEquals(CloseCodes.MOVED, awaitClose(client, 15_000));
        long closedAfter = System.currentTimeMillis() - pausedAt;
        assertTrue(client.session.hasPending(), "보내지 못한 편집은 클라이언트에 남아 있다");
        // 그동안 API 는 503 이다. 로그인한 사용자도 401 로 쫓겨나지 않는다.
        assertEquals(503, send("POST", "/api/docs/" + page + "/ws-ticket", null, token).statusCode());

        unpause();
        long resumedAt = System.currentTimeMillis();
        connect(client, page, token);
        while ((client.session.hasPending() || !client.connected()) && client.process(5_000)) {
            // 다시 보낸 편집의 ack 를 기다린다.
        }
        long recoveredAfter = System.currentTimeMillis() - resumedAt;

        // 새 클라이언트로 열어 서버의 문서를 본다. "나" 는 한 번만 들어 있다.
        TestClient fresh = new TestClient("check");
        connect(fresh, page, token);
        fresh.process(5_000);
        assertEquals("가나", fresh.session.view().get(page).text().plainText());
        System.out.println("PG 정지 후 세션 버림까지 " + closedAfter + "ms, DB 복구 후 다시 확정까지 " + recoveredAfter + "ms");
    }
}
