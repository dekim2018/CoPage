package com.copage.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 구글, 깃허브 로그인 (A-4, D-54). 클라이언트 ID 와 비밀을 둘 다 주었을 때만 그 서비스로 로그인할 수 있다. 비밀은 저장소에 두지
 * 않고 환경 변수로 준다. 서비스에 등록할 돌아오는 주소는 https://도메인/login/oauth2/code/google(또는 github)다.
 *
 * @param githubEmailsUrl 깃허브의 이메일 목록 API. 시험이 가짜 서비스로 바꾼다
 */
@ConfigurationProperties("copage.oauth")
public record OAuthProperties(
        String googleClientId,
        String googleClientSecret,
        String githubClientId,
        String githubClientSecret,
        @DefaultValue("https://api.github.com/user/emails") String githubEmailsUrl) {

    boolean google() {
        return present(googleClientId) && present(googleClientSecret);
    }

    boolean github() {
        return present(githubClientId) && present(githubClientSecret);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
