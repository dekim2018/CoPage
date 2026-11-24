package com.copage.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 모듈들이 함께 쓰는 웹 설정.
 *
 * @param allowedOrigins 브라우저에서 API(CORS)와 WebSocket 핸드셰이크, 토큰 재발급을 부를 수 있는 Origin. 비어 있으면 같은
 *     출처만 받는다
 * @param nodeId 이 노드의 이름. 비어 있으면 시작할 때 무작위로 만든다 (NodeIdentity)
 * @param rateLimit 로그인 없이 부를 수 있는 경로의 IP 별 빈도 제한을 켤지. 기본은 켠다. 개발 프로필과 시험은 끈다 (D-48)
 */
@ConfigurationProperties("copage.web")
public record WebProperties(@DefaultValue List<String> allowedOrigins, String nodeId, @DefaultValue("true") boolean rateLimit) {
}
