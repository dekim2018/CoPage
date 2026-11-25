package com.copage.identity.config;

import java.util.List;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * 켜진 다른 서비스 로그인 (A-4, D-54). 하나도 없으면 repository 는 null 이고 /oauth2 경로는 막힌다.
 *
 * @param ids 로그인 화면에 단추를 그릴 서비스 이름(google, github)
 */
public record OAuthProviders(ClientRegistrationRepository repository, List<String> ids) {
}
