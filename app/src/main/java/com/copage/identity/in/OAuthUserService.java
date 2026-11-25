package com.copage.identity.in;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;

/**
 * OIDC 가 아닌 서비스의 사용자 정보 (A-4, D-53). 깃허브는 공개 이메일이 없을 수 있고 확인 여부도 주지 않아, 이메일 목록에서
 * 확인된 주 이메일을 골라 email, email_verified 로 채운다. 다른 서비스는 받은 그대로다.
 */
public final class OAuthUserService extends DefaultOAuth2UserService {

    private final RestClient http = RestClient.create();
    private final String githubEmails;

    /** @param githubEmails 깃허브의 이메일 목록 API 주소 */
    public OAuthUserService(String githubEmails) {
        this.githubEmails = githubEmails;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        if (!"github".equals(request.getClientRegistration().getRegistrationId())) {
            return user;
        }
        List<Map<String, Object>> emails = http.get()
                .uri(githubEmails)
                .header("Authorization", "Bearer " + request.getAccessToken().getTokenValue())
                .accept(MediaType.valueOf("application/vnd.github+json"))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        Map<String, Object> attributes = new HashMap<>(user.getAttributes());
        attributes.remove("email");
        attributes.put("email_verified", false);
        if (emails != null) {
            for (Map<String, Object> email : emails) {
                if (Boolean.TRUE.equals(email.get("primary")) && Boolean.TRUE.equals(email.get("verified"))) {
                    attributes.put("email", email.get("email"));
                    attributes.put("email_verified", true);
                }
            }
        }
        String nameKey = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint()
                .getUserNameAttributeName();
        return new DefaultOAuth2User(user.getAuthorities(), attributes, nameKey);
    }
}
