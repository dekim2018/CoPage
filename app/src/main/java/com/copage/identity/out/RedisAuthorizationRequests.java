package com.copage.identity.out;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구글, 깃허브 로그인 도중의 요청(상태 값, PKCE 검증값, nonce)을 Redis 에 state 로 둔다 (A-4, D-53). 서버 세션에 두면 노드가
 * 여럿일 때 돌아오는 요청이 다른 노드로 가서 실패한다. 10분 뒤 사라지고, 한 번 쓰면 지운다. 값은 글자뿐이라 JSON 으로 둔다
 * (자바 직렬화를 쓰지 않는다).
 */
public final class RedisAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;

    public RedisAuthorizationRequests(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        String json = redis.opsForValue().get(key(state));
        return json == null ? null : read(json);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            return;
        }
        Map<String, Object> value = new HashMap<>();
        value.put("authorizationUri", authorizationRequest.getAuthorizationUri());
        value.put("clientId", authorizationRequest.getClientId());
        value.put("redirectUri", authorizationRequest.getRedirectUri());
        value.put("scopes", List.copyOf(authorizationRequest.getScopes()));
        value.put("state", authorizationRequest.getState());
        value.put("additionalParameters", authorizationRequest.getAdditionalParameters());
        value.put("attributes", authorizationRequest.getAttributes());
        value.put("authorizationRequestUri", authorizationRequest.getAuthorizationRequestUri());
        redis.opsForValue().set(key(authorizationRequest.getState()), mapper.writeValueAsString(value), TTL);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        String json = redis.opsForValue().getAndDelete(key(state));
        return json == null ? null : read(json);
    }

    @SuppressWarnings("unchecked")
    private OAuth2AuthorizationRequest read(String json) {
        Map<String, Object> value = mapper.readValue(json, MAP);
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri((String) value.get("authorizationUri"))
                .clientId((String) value.get("clientId"))
                .redirectUri((String) value.get("redirectUri"))
                .scopes(Set.copyOf((List<String>) value.get("scopes")))
                .state((String) value.get("state"))
                .additionalParameters((Map<String, Object>) value.get("additionalParameters"))
                .attributes((Map<String, Object>) value.get("attributes"))
                .authorizationRequestUri((String) value.get("authorizationRequestUri"))
                .build();
    }

    private static String key(String state) {
        return "copage:oauth-request:" + state;
    }
}
