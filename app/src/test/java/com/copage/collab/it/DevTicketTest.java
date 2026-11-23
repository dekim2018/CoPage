package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.copage.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 개발용 티켓 발급은 dev 프로필에서만 열린다. */
class DevTicketTest {

    private static HttpResponse<String> issue(int port, UUID docId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dev/docs/" + docId + "/ws-ticket"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"COMMENT\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("dev")
    class Dev_프로필 extends TestDatabase {

        @LocalServerPort
        int port;

        @Test
        void 티켓을_받아_문서에_붙을_수_있다() throws Exception {
            UUID docId = UUID.randomUUID();
            HttpResponse<String> response = issue(port, docId);
            assertEquals(200, response.statusCode());
            JsonNode body = JsonMapper.builder().build().readTree(response.body());

            TestClient client = new TestClient("dev");
            client.connect(URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket="
                    + body.get("ticket").stringValue() + "&v=1"));
            JsonNode welcome = client.take();

            assertEquals("welcome", welcome.get("t").stringValue());
            assertEquals("COMMENT", welcome.get("role").stringValue());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class 기본_프로필 extends TestDatabase {

        @LocalServerPort
        int port;

        @Test
        void 티켓_발급_경로가_없다() throws Exception {
            assertEquals(404, issue(port, UUID.randomUUID()).statusCode());
        }
    }
}
