package com.copage.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.support.TestInfra;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 에러 모니터링 (D-49). DSN 을 이 시험의 가짜 수집 서버로 두고, ERROR 로그가 실제로 나가며 그 안의 티켓과 토큰이 가려지는지
 * 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErrorReportFlowTest.Boom.class)
class ErrorReportFlowTest extends TestInfra {

    /** 처리하지 못한 예외를 내는 시험용 입구. */
    @TestComponent
    @RestController
    static class Boom {
        @GetMapping("/api/test/boom")
        String boom() {
            throw new IllegalStateException("처리하지 못한 시험 예외");
        }

        @GetMapping("/api/test/not-found")
        String notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "처리한 시험 404");
        }
    }

    @LocalServerPort
    int port;

    private static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private static final HttpServer COLLECTOR = startCollector();

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                InputStream body = exchange.getRequestBody();
                byte[] raw = body.readAllBytes();
                boolean gzip = "gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"));
                RECEIVED.add(new String(gzip ? new GZIPInputStream(new ByteArrayInputStream(raw)).readAllBytes() : raw,
                        StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void sentry(DynamicPropertyRegistry registry) {
        registry.add("sentry.dsn", () -> "http://public@127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/1");
        registry.add("sentry.environment", () -> "test");
    }

    @AfterAll
    static void stop() {
        COLLECTOR.stop(0);
    }

    @Test
    void ERROR_로그는_보내고_주소의_티켓과_토큰은_가린다() throws Exception {
        LoggerFactory.getLogger("copage.test").error(
                "모니터링 시험 /ws/docs/x?ticket=SECRET-TICKET-1 /s/SECRET-SHARE-2 Authorization: Bearer SECRET-JWT-3");

        String event = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (event == null && System.currentTimeMillis() < deadline) {
            String envelope = RECEIVED.poll(1, TimeUnit.SECONDS);
            if (envelope != null && envelope.contains("모니터링 시험")) {
                event = envelope;
            }
        }
        assertTrue(event != null, "수집 서버가 이벤트를 받지 못했다");
        assertFalse(event.contains("SECRET-"), event);
        assertTrue(event.contains("ticket=REDACTED"), event);
        assertTrue(event.contains("/s/REDACTED"), event);
        assertTrue(event.contains("Bearer REDACTED"), event);
        assertTrue(event.contains("\"environment\":\"test\""), event);
    }

    private String awaitEvent(String text) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String envelope = RECEIVED.poll(1, TimeUnit.SECONDS);
            if (envelope != null && envelope.contains(text)) {
                return envelope;
            }
        }
        return null;
    }

    @Test
    void 처리하지_못한_예외는_보내고_처리한_4xx_는_보내지_않는다() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String json = "{\"email\":\"" + email + "\",\"password\":\"correct horse 1\",\"name\":\"a\"}";
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        String login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String token = login.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        int notFound = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test/not-found"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        assertEquals(404, notFound);
        int boom = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/test/boom?ticket=SECRET-TICKET-9"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        assertEquals(500, boom);

        String event = awaitEvent("처리하지 못한 시험 예외");
        assertTrue(event != null, "500 을 보내지 않았다");
        assertFalse(event.contains("SECRET-"), event);
        assertFalse(event.contains(token), "Access 토큰");
        // 404 는 500 보다 먼저 일어났다. 500 을 받을 때까지 404 의 이벤트는 오지 않았어야 한다.
        assertFalse(RECEIVED.stream().anyMatch(envelope -> envelope.contains("처리한 시험 404")));
    }

    @Test
    void 가리기_규칙() {
        assertEquals("/api/share/REDACTED/ws-ticket", ErrorReportScrubber.scrub("/api/share/abc123/ws-ticket"));
        assertEquals("/invite/REDACTED", ErrorReportScrubber.scrub("/invite/tok"));
        assertEquals("ticket=REDACTED&v=1", ErrorReportScrubber.scrub("ticket=abc&v=1"));
        assertEquals("/api/docs/1/comments", ErrorReportScrubber.scrub("/api/docs/1/comments"));
        assertEquals(null, ErrorReportScrubber.scrub(null));
    }
}
