package com.copage.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST 호출. 가입, 로그인, 워크스페이스와 페이지, 멤버, 티켓. */
final class Api {

    static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PASSWORD = "loadgen password 1";

    record Account(String id, String email, String token) {
    }

    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();

    Api(String base) {
        this.base = base;
    }

    JsonNode send(String method, String path, Object json, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
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
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(method + " " + path + " " + response.statusCode() + " " + response.body());
            }
            return response.body().isEmpty() ? null : MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    Account account(String name) {
        String email = "load-" + UUID.randomUUID() + "@example.com";
        JsonNode user = send("POST", "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "name", name), null);
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), null);
        return new Account(user.get("id").stringValue(), email, login.get("accessToken").stringValue());
    }

    String workspace(Account owner) {
        return send("POST", "/api/workspaces", Map.of("name", "부하"), owner.token()).get("id").stringValue();
    }

    void addMember(Account owner, String workspaceId, Account member) {
        send("POST", "/api/workspaces/" + workspaceId + "/members", Map.of("email", member.email(), "role", "MEMBER"),
                owner.token());
    }

    String page(Account owner, String workspaceId) {
        return send("POST", "/api/workspaces/" + workspaceId + "/pages", Map.of(), owner.token()).get("id").stringValue();
    }

    String ticket(Account who, String pageId) {
        return send("POST", "/api/docs/" + pageId + "/ws-ticket", null, who.token()).get("ticket").stringValue();
    }

    /**
     * 관리 포트의 Prometheus 지표 값. 이름이 같은 줄(레이블이 달라도)을 모두 더한다. 이름에 레이블 앞부분을 붙이면
     * ({@code jvm_memory_used_bytes{area="heap"}) 그 레이블을 가진 줄만 더한다. 없으면 NaN.
     */
    double metric(String managementBase, String name) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(managementBase + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            double sum = Double.NaN;
            for (String line : response.body().split("\n")) {
                boolean match = name.contains("{") ? line.startsWith(name)
                        : line.startsWith(name + " ") || line.startsWith(name + "{");
                if (match) {
                    double value = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                    sum = Double.isNaN(sum) ? value : sum + value;
                }
            }
            return sum;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
