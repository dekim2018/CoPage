package com.copage.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.support.TestInfra;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 다른 서비스 계정 로그인 (A-4, D-54). 이 시험이 띄운 가짜 OAuth 서비스(인가, 토큰, 사용자 정보)로 처음부터 끝까지 지난다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuthLoginFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 가짜 서비스가 다음 로그인에 돌려줄 사용자. */
    private static final AtomicReference<Map<String, Object>> NEXT_USER = new AtomicReference<>();
    private static final AtomicReference<List<Map<String, Object>>> NEXT_EMAILS = new AtomicReference<>(List.of());
    private static final HttpServer PROVIDER = startProvider();

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 동의 화면 없이 바로 돌아간다. 받은 state 를 그대로 싣는다.
            server.createContext("/authorize", exchange -> {
                Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                String location = query.get("redirect_uri") + "?code=fake-code&state="
                        + URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/token", exchange -> {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, "{\"access_token\":\"fake-token\",\"token_type\":\"Bearer\",\"expires_in\":60}");
            });
            server.createContext("/userinfo", exchange -> respond(exchange, MAPPER.writeValueAsString(NEXT_USER.get())));
            // 깃허브처럼 사용자 정보에는 이메일이 없고 목록에 따로 있다.
            server.createContext("/user/emails", exchange -> respond(exchange, MAPPER.writeValueAsString(NEXT_EMAILS.get())));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        registry.add("spring.security.oauth2.client.registration.fake.client-id", () -> "fake-client");
        registry.add("spring.security.oauth2.client.registration.fake.client-secret", () -> "fake-secret");
        registry.add("spring.security.oauth2.client.registration.fake.authorization-grant-type", () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.fake.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.fake.scope", () -> "profile");
        registry.add("spring.security.oauth2.client.provider.fake.authorization-uri", () -> base + "/authorize");
        registry.add("spring.security.oauth2.client.provider.fake.token-uri", () -> base + "/token");
        registry.add("spring.security.oauth2.client.provider.fake.user-info-uri", () -> base + "/userinfo");
        registry.add("spring.security.oauth2.client.provider.fake.user-name-attribute", () -> "id");
        // 깃허브 이름으로 같은 가짜 서비스를 하나 더 둔다. 이메일은 목록 API 에서 고른다.
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "fake-client");
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "fake-secret");
        registry.add("spring.security.oauth2.client.registration.github.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.github.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.github.provider", () -> "fake");
        registry.add("copage.oauth.github-emails-url", () -> base + "/user/emails");
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop(0);
    }

    @LocalServerPort
    int port;

    /**
     * 브라우저처럼 주소를 따라간다(다른 서비스로 가는 것도). Refresh 쿠키는 Secure 라 http 로는 쿠키 관리자가 보내지 않으므로
     * Set-Cookie 를 직접 읽어 둔다(AuthFlowTest 와 같다).
     */
    private static final class Browser {
        final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String refreshCookie;

        static Browser open() {
            return new Browser();
        }

        HttpClient http() {
            return http;
        }

        void remember(HttpResponse<?> response) {
            response.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("copage_refresh="))
                    .findFirst()
                    .ifPresent(value -> refreshCookie = value.substring("copage_refresh=".length(), value.indexOf(';')));
        }
    }

    private String login(Browser browser, Map<String, Object> user) throws Exception {
        return login(browser, "fake", user);
    }

    private String login(Browser browser, String provider, Map<String, Object> user) throws Exception {
        NEXT_USER.set(user);
        URI next = URI.create("http://localhost:" + port + "/oauth2/authorization/" + provider);
        for (int hops = 0; hops < 5; hops++) {
            HttpResponse<String> response = browser.http().send(HttpRequest.newBuilder(next).build(),
                    HttpResponse.BodyHandlers.ofString());
            browser.remember(response);
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                throw new AssertionError("돌아갈 곳이 없다: " + response.statusCode() + " " + response.body());
            }
            URI target = next.resolve(location);
            if (target.getPort() == port && !target.getPath().startsWith("/login/oauth2")) {
                return target.getPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
            }
            next = target;
        }
        throw new AssertionError("너무 많이 돌았다");
    }

    private JsonNode refresh(Browser browser) throws Exception {
        if (browser.refreshCookie == null) {
            return null;
        }
        HttpResponse<String> response = browser.http().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                        .header("Cookie", "copage_refresh=" + browser.refreshCookie)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        browser.remember(response);
        return response.statusCode() == 200 ? MAPPER.readTree(response.body()) : null;
    }

    private static Map<String, Object> user(String id, String email, boolean verified, String name) {
        Map<String, Object> user = new HashMap<>();
        user.put("id", id);
        user.put("email", email);
        user.put("email_verified", verified);
        user.put("name", name);
        return user;
    }

    @Test
    void 처음이면_계정을_만들고_다시_오면_같은_계정으로_들어간다() throws Exception {
        String subject = "fake-" + UUID.randomUUID();
        String email = "oauth-" + UUID.randomUUID() + "@example.com";
        Browser browser = Browser.open();

        assertEquals("/", login(browser, user(subject, email, true, "구글 사람")));
        JsonNode session = refresh(browser);
        assertTrue(session != null, "Refresh 쿠키를 받았다");
        assertEquals(email, session.get("user").get("email").stringValue());
        assertEquals("구글 사람", session.get("user").get("name").stringValue());
        String userId = session.get("user").get("id").stringValue();

        // 다른 브라우저에서 다시 들어오면 같은 사용자다. 이메일이 바뀌어도 서비스 안의 ID 로 찾는다.
        Browser again = Browser.open();
        assertEquals("/", login(again, user(subject, "changed-" + email, true, "구글 사람")));
        assertEquals(userId, refresh(again).get("user").get("id").stringValue());

        // 비밀번호가 없는 계정이라 비밀번호 로그인은 되지 않는다.
        HttpResponse<String> password = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"anything 123\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, password.statusCode());
    }

    @Test
    void 확인되지_않은_이메일이나_비밀번호로_가입한_이메일이면_들어가지_못한다() throws Exception {
        assertEquals("/login?oauthError=email-not-verified", login(Browser.open(),
                user("fake-" + UUID.randomUUID(), "u-" + UUID.randomUUID() + "@example.com", false, "a")));

        String email = "taken-" + UUID.randomUUID() + "@example.com";
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/signup"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"correct horse 1\",\"name\":\"a\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Browser browser = Browser.open();
        assertEquals("/login?oauthError=account-exists",
                login(browser, user("fake-" + UUID.randomUUID(), email.toUpperCase(), true, "a")));
        assertEquals(null, refresh(browser), "쿠키를 주지 않았다");
    }

    @Test
    void 꾸민_상태_값으로_돌아오면_실패한다() throws Exception {
        Browser browser = Browser.open();
        HttpResponse<String> response = browser.http().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/login/oauth2/code/fake?code=fake-code&state=forged"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("/login?oauthError=failed", response.headers().firstValue("Location").orElse(""));
        assertEquals(null, refresh(browser));
    }

    @Test
    void 켜진_서비스를_알려_준다() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/providers")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("\"fake\"") && response.body().contains("\"github\""), response.body());
    }

    @Test
    void 깃허브는_이메일_목록에서_확인된_주_이메일을_쓴다() throws Exception {
        String primary = "gh-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", "gh-" + UUID.randomUUID());
        profile.put("login", "octo");
        NEXT_EMAILS.set(List.of(
                Map.of("email", "other-" + UUID.randomUUID() + "@example.com", "primary", false, "verified", true),
                Map.of("email", primary, "primary", true, "verified", true)));
        Browser browser = Browser.open();
        assertEquals("/", login(browser, "github", profile));
        JsonNode user = refresh(browser).get("user");
        assertEquals(primary, user.get("email").stringValue());
        assertEquals("octo", user.get("name").stringValue(), "이름이 없으면 로그인 이름");

        // 주 이메일이 확인되지 않았으면 들어가지 못한다.
        profile.put("id", "gh-" + UUID.randomUUID());
        NEXT_EMAILS.set(List.of(Map.of("email", "x-" + UUID.randomUUID() + "@example.com", "primary", true,
                "verified", false)));
        assertEquals("/login?oauthError=email-not-verified", login(Browser.open(), "github", profile));
    }
}
