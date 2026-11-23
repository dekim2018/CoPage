package com.copage.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 모듈들이 함께 쓰는 웹 설정.
 *
 * @param allowedOrigins 브라우저에서 API(CORS)와 WebSocket 핸드셰이크, 토큰 재발급을 부를 수 있는 Origin. 비어 있으면 같은
 *     출처만 받는다
 */
@ConfigurationProperties("copage.web")
public record WebProperties(@DefaultValue List<String> allowedOrigins) {
}
