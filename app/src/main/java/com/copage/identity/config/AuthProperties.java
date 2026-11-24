package com.copage.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 설정. 기본값은 docs/07-SECURITY.md 9.1 을 따른다.
 *
 * @param jwtSecret Access 토큰 서명 키(HS256). UTF-8 로 32 바이트 이상이어야 한다. 저장소에 두지 않는다
 * @param cookieSecure Refresh 토큰 쿠키의 Secure 속성. 브라우저는 localhost 에서는 http 로도 Secure 쿠키를 보낸다
 * @param refreshReuseGrace 쓴 Refresh 토큰이 이 시간 안에 다시 오면 탈취가 아니라 동시 재발급으로 본다
 * @param issuer Access 토큰의 발급자(iss). 다른 값의 토큰은 받지 않는다 (진단 SEC-5)
 * @param audience Access 토큰의 대상(aud). 이 값이 없는 토큰은 받지 않는다
 */
@ConfigurationProperties("copage.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue("5") int loginMaxFailures,
        @DefaultValue("5m") Duration loginWindow,
        @DefaultValue("5m") Duration loginLock,
        @DefaultValue("copage") String issuer,
        @DefaultValue("copage-api") String audience) {
}
