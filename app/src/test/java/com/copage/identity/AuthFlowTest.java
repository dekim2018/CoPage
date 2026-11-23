package com.copage.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.it.TestClient;
import com.copage.support.TestInfra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 가입부터 WebSocket 연결까지, 그리고 토큰 회전과 로그아웃과 잠금을 실제 서버와 PostgreSQL, Redis 로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"copage.web.allowed-origins=http://allowed.example", "copage.auth.refresh-reuse-grace=1s"})
class AuthFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse 1";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body, String refreshCookie) {
        String accessToken() {
            return body.get("accessToken").stringValue();
        }
    }

    private Response send(String method, String path, String json, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // Secure 쿠키는 http 로 보내지 않으므로 테스트가 Set-Cookie 를 직접 읽어 Cookie 헤더로 돌려준다.
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("copage_refresh="))
                .map(value -> value.substring("copage_refresh=".length(), value.indexOf(';')))
                .findFirst()
                .orElse(null);
        // 허용하지 않은 Origin 은 CORS 필터가 컨트롤러 앞에서 일반 텍스트로 거절한다. JSON 이 아닌 본문은 null 로 둔다.
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        JsonNode body = isJson && !response.body().isEmpty() ? MAPPER.readTree(response.body()) : null;
        return new Response(response.statusCode(), body, cookie);
    }

    private static String newEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    private Response signup(String email, String password, String name) throws Exception {
        return send("POST", "/api/auth/signup", MAPPER.writeValueAsString(Map.of(
                "email", email, "password", password, "name", name)));
    }

    private Response login(String email, String password) throws Exception {
        return send("POST", "/api/auth/login", MAPPER.writeValueAsString(Map.of(
                "email", email, "password", password)));
    }

    private Response refresh(String cookie, String... headers) throws Exception {
        String[] all = new String[headers.length + 2];
        all[0] = "Cookie";
        all[1] = "copage_refresh=" + cookie;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send("POST", "/api/auth/refresh", null, all);
    }

    private Response me(String accessToken) throws Exception {
        return send("GET", "/api/me", null, "Authorization", "Bearer " + accessToken);
    }

    @Test
    void 가입하고_로그인해서_티켓을_받아_문서에_붙는다() throws Exception {
        String email = newEmail();
        Response created = signup(email.toUpperCase(), PASSWORD, " 김코 ");
        assertEquals(201, created.status());
        assertEquals(email, created.body().get("email").stringValue());
        assertEquals("김코", created.body().get("name").stringValue());

        Response session = login(" " + email + " ", PASSWORD);
        assertEquals(200, session.status());
        assertEquals("Bearer", session.body().get("tokenType").stringValue());
        assertTrue(session.refreshCookie() != null && !session.refreshCookie().isEmpty());
        assertEquals(email, me(session.accessToken()).body().get("email").stringValue());

        UUID docId = UUID.randomUUID();
        Response ticket = send("POST", "/api/docs/" + docId + "/ws-ticket", null,
                "Authorization", "Bearer " + session.accessToken());
        assertEquals(200, ticket.status());

        TestClient client = new TestClient("tab");
        client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket="
                + ticket.body().get("ticket").stringValue() + "&v=1"));
        JsonNode welcome = client.take();
        assertEquals("welcome", welcome.get("t").stringValue());
        assertEquals("EDIT", welcome.get("role").stringValue());
    }

    @Test
    void 가입_입력이_틀리면_400_같은_이메일이면_409_를_ProblemDetail_로_준다() throws Exception {
        String email = newEmail();
        assertEquals(201, signup(email, PASSWORD, "a").status());

        Response duplicate = signup(email.toUpperCase(), PASSWORD, "b");
        assertEquals(409, duplicate.status());
        assertEquals(409, duplicate.body().get("status").intValue());
        assertEquals(400, signup(newEmail(), "short", "a").status());
        assertEquals(400, signup("not-an-email", PASSWORD, "a").status());
        // 한글 25 글자는 64 글자 안이지만 UTF-8 로 75 바이트라 BCrypt 가 뒤를 잘라 버린다.
        assertEquals(400, signup(newEmail(), "가".repeat(25), "a").status());
    }

    @Test
    void 없는_이메일과_틀린_비밀번호는_같은_응답을_받는다() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");

        Response wrongPassword = login(email, "wrong password");
        Response unknown = login(newEmail(), PASSWORD);

        assertEquals(401, wrongPassword.status());
        assertEquals(401, unknown.status());
        assertEquals(wrongPassword.body().get("detail"), unknown.body().get("detail"));
    }

    @Test
    void 다섯_번_틀리면_맞는_비밀번호도_잠시_막는다() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(email, "wrong password").status());
        }

        assertEquals(429, login(email, PASSWORD).status());
    }

    @Test
    void 재발급하면_Refresh_토큰이_바뀌고_쓴_토큰이_다시_오면_모든_세션을_끊는다() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");
        Response first = login(email, PASSWORD);

        Response second = refresh(first.refreshCookie());
        assertEquals(200, second.status());
        assertNotEquals(first.refreshCookie(), second.refreshCookie());
        assertEquals(200, me(second.accessToken()).status());

        // 유예 시간(1초)이 지난 뒤 이미 쓴 첫 토큰이 다시 왔다. 훔친 토큰으로 본다.
        Thread.sleep(1_100);
        Response reused = refresh(first.refreshCookie());
        assertEquals(401, reused.status());
        assertEquals("", reused.refreshCookie(), "쿠키를 지운다");

        assertEquals(401, refresh(second.refreshCookie()).status(), "그 사이 받은 Refresh 토큰도 막힌다");
        assertEquals(401, me(second.accessToken()).status(), "그때까지 발급한 Access 토큰도 막힌다");
        assertEquals(401, me(first.accessToken()).status());

        // 다시 로그인하면 쓸 수 있다. 막는 기준이 초 단위라 다음 초로 넘긴다.
        Thread.sleep(1_100);
        assertEquals(200, me(login(email, PASSWORD).accessToken()).status());
    }

    @Test
    void 같은_토큰으로_동시에_재발급하면_뒤의_요청만_409_를_받고_세션은_그대로다() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");
        Response session = login(email, PASSWORD);

        // 탭 두 개가 같은 쿠키로 거의 동시에 재발급한다.
        Response first = refresh(session.refreshCookie());
        Response second = refresh(session.refreshCookie());

        assertEquals(200, first.status());
        assertEquals(409, second.status());
        assertEquals(null, second.refreshCookie(), "첫 요청이 내려 준 새 쿠키를 지우지 않는다");
        assertEquals(200, me(first.accessToken()).status());
        assertEquals(200, me(session.accessToken()).status());
        assertEquals(200, refresh(first.refreshCookie()).status());
    }

    @Test
    void 로그아웃하면_그_Access_토큰과_Refresh_토큰을_쓸_수_없다() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");
        Response session = login(email, PASSWORD);

        Response out = send("POST", "/api/auth/logout", null,
                "Authorization", "Bearer " + session.accessToken(), "Cookie", "copage_refresh=" + session.refreshCookie());

        assertEquals(204, out.status());
        assertEquals(401, me(session.accessToken()).status());
        assertEquals(401, refresh(session.refreshCookie()).status());
    }

    @Test
    void 재발급은_허용하지_않은_Origin_에서_오면_403() throws Exception {
        String email = newEmail();
        signup(email, PASSWORD, "a");
        Response session = login(email, PASSWORD);

        assertEquals(403, refresh(session.refreshCookie(), "Origin", "http://evil.example").status());
        assertEquals(200, refresh(session.refreshCookie(), "Origin", "http://allowed.example").status());
    }

    @Test
    void 토큰이_없거나_다른_키로_서명했으면_401() throws Exception {
        UUID docId = UUID.randomUUID();
        assertEquals(401, send("POST", "/api/docs/" + docId + "/ws-ticket", null).status());
        assertEquals(401, me("not.a.jwt").status());

        // 서명만 다른 토큰. 헤더와 내용은 정상이다.
        String email = newEmail();
        signup(email, PASSWORD, "a");
        String token = login(email, PASSWORD).accessToken();
        String forged = token.substring(0, token.lastIndexOf('.') + 1) + "c2lnbmF0dXJl";
        assertEquals(401, me(forged).status());
    }

    @Test
    void API_와_WebSocket_밖의_경로는_막는다() throws Exception {
        assertEquals(403, send("GET", "/anything", null).status());
    }
}
