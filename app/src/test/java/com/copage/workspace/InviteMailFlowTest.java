package com.copage.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 초대 메일 (W-1, D-50). 메일을 받아 보관하는 Mailpit 으로 실제 SMTP 를 거쳐 받은 메일의 링크를 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteMailFlowTest extends TestInfra {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final GenericContainer<?> MAILPIT = startMailpit();

    private static GenericContainer<?> startMailpit() {
        GenericContainer<?> container = new GenericContainer<>("axllent/mailpit:v1.34.0").withExposedPorts(1025, 8025);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("copage.mail.from", () -> "noreply@copage.example.com");
        registry.add("copage.mail.public-url", () -> "https://copage.example.com/");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode call(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return MAPPER.readTree(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode mailpit(String path) throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
        return MAPPER.readTree(http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void 메일_서버가_있으면_초대한_이메일로_링크를_보낸다() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/auth/signup", Map.of("email", email, "password", "correct horse 1", "name", "주인"), null);
        String token = call("POST", "/api/auth/login", Map.of("email", email, "password", "correct horse 1"), null)
                .get("accessToken").stringValue();
        String workspace = call("POST", "/api/workspaces", Map.of("name", "메일 팀"), token).get("id").stringValue();
        String invited = "invited-" + UUID.randomUUID() + "@example.com";

        JsonNode created = call("POST", "/api/workspaces/" + workspace + "/invites",
                Map.of("email", invited, "role", "MEMBER"), token);
        assertTrue(created.get("mailed").booleanValue());

        JsonNode message = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (message == null && System.currentTimeMillis() < deadline) {
            for (JsonNode item : mailpit("/api/v1/messages").get("messages")) {
                if (item.get("To").get(0).get("Address").stringValue().equals(invited)) {
                    message = mailpit("/api/v1/message/" + item.get("ID").stringValue());
                }
            }
            Thread.sleep(200);
        }
        assertTrue(message != null, "메일이 오지 않았다");
        assertEquals("noreply@copage.example.com", message.get("From").get("Address").stringValue());
        assertTrue(message.get("Subject").stringValue().contains("메일 팀"));
        String text = message.get("Text").stringValue();
        assertTrue(text.contains("https://copage.example.com/invite/" + created.get("token").stringValue()), text);
        assertTrue(text.contains("주인 님이"), text);
    }
}
