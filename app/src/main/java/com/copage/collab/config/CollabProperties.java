package com.copage.collab.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 협업 모듈의 설정 값. 기본값은 docs/05-PROTOCOL.md 7.7, docs/03-ARCHITECTURE.md 5.2, docs/07-SECURITY.md 9.3 을 따른다.
 *
 * @param allowedOrigins WebSocket 핸드셰이크에서 받는 Origin. 비어 있으면 같은 출처만 받는다
 * @param maxTextLength 문서 하나의 글자 수 한도. UTF-16 코드 단위로 센다
 * @param rebaseMaxGap 재접속할 때 이보다 리비전 차이가 크면 대기 편집을 합치지 않게 한다 (7.5)
 * @param rebaseMaxAge 재접속할 때 이보다 오래 확인하지 못했으면 대기 편집을 합치지 않게 한다 (7.5)
 */
@ConfigurationProperties("copage.collab")
public record CollabProperties(
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("30") int maxChangesPerSecond,
        @DefaultValue("5000") int maxBlocks,
        @DefaultValue("2097152") long maxTextLength,
        @DefaultValue("100") int maxConnectionsPerDocument,
        @DefaultValue("1048576") int sendBufferBytes,
        @DefaultValue("10s") Duration sendTimeLimit,
        @DefaultValue("45s") Duration idleTimeout,
        @DefaultValue("60s") Duration sessionUnloadDelay,
        @DefaultValue("30s") Duration ticketTtl,
        @DefaultValue("5000") long rebaseMaxGap,
        @DefaultValue("7d") Duration rebaseMaxAge) {
}
