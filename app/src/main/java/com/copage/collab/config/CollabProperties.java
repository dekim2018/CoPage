package com.copage.collab.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 협업 모듈의 설정 값. 기본값은 docs/05-PROTOCOL.md 7.7, docs/03-ARCHITECTURE.md 5.2, docs/07-SECURITY.md 9.3 을 따른다.
 *
 * @param maxMessageBytes 클라이언트 메시지 하나의 바이트 한도. 넘으면 4008. 화면 클라이언트는 묶음을 60,000 바이트 안으로 나눠
 *     보내므로(frontend sendBudget.ts 의 MAX_CHANGE_BYTES, D-37) 이보다 작게 줄이면 정상 클라이언트도 끊긴다
 * @param maxPresencePerSecond 연결마다 초당 받을 presence 수. 넘는 것은 연결을 끊지 않고 버린다
 * @param maxTextLength 문서 하나의 글자 수 한도. UTF-16 코드 단위로 센다
 * @param rebaseMaxGap 재접속할 때 이보다 리비전 차이가 크면 대기 편집을 합치지 않게 한다 (7.5)
 * @param rebaseMaxAge 재접속할 때 이보다 오래 확인하지 못했으면 대기 편집을 합치지 않게 한다 (7.5)
 * @param snapshotInterval 마지막 스냅샷 이후 확정된 묶음이 이만큼 쌓이면 스냅샷을 남긴다 (docs/06-DATA-MODEL.md 8.3)
 * @param snapshotsKept 문서마다 남길 스냅샷 수
 * @param historyWindow 세션이 들고 있을 최근 확정 기록 수. 이보다 오래된 기준 리비전의 묶음을 보낸 연결은 4011 로 닫아
 *     재접속하게 한다. 1 이상
 * @param leaseTtl 문서 소유 리스의 만료. 주인 노드가 죽으면 다른 노드는 이만큼 기다렸다 맡는다 (5.3, D-43)
 * @param leaseRenewInterval 리스를 이어 쥐는 간격. 만료의 1/3 쯤
 * @param handoffGrace 막 잡은 문서는 이 시간 동안 넘겨 달라는 요청을 받아도 넘기지 않는다. 노드 사이를 오가는 것을 막는다
 */
@ConfigurationProperties("copage.collab")
public record CollabProperties(
        @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("30") int maxChangesPerSecond,
        @DefaultValue("30") int maxPresencePerSecond,
        @DefaultValue("5000") int maxBlocks,
        @DefaultValue("2097152") long maxTextLength,
        @DefaultValue("100") int maxConnectionsPerDocument,
        @DefaultValue("1048576") int sendBufferBytes,
        @DefaultValue("10s") Duration sendTimeLimit,
        @DefaultValue("45s") Duration idleTimeout,
        @DefaultValue("60s") Duration sessionUnloadDelay,
        @DefaultValue("30s") Duration ticketTtl,
        @DefaultValue("5000") long rebaseMaxGap,
        @DefaultValue("7d") Duration rebaseMaxAge,
        @DefaultValue("200") long snapshotInterval,
        @DefaultValue("3") int snapshotsKept,
        @DefaultValue("5000") int historyWindow,
        @DefaultValue("6s") Duration leaseTtl,
        @DefaultValue("2s") Duration leaseRenewInterval,
        @DefaultValue("5s") Duration handoffGrace) {
}
