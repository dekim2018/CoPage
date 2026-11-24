package com.copage.collab.out;

import com.copage.collab.app.CollabMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 협업 지표를 Micrometer 로 남긴다 (docs/11-TEST-PLAN.md 13.4). 레이블은 nack 의 분류 하나뿐이고 가짓수가 정해져 있다. 문서 ID 와
 * 사용자 ID 는 레이블에 넣지 않는다.
 *
 * <p>분포는 히스토그램(구간별 횟수)으로 낸다. p50, p95, p99 는 수집하는 쪽에서 histogram_quantile 로 계산한다. 노드마다 계산한
 * 백분위는 노드가 여럿일 때 합칠 수 없지만 히스토그램은 합칠 수 있다 (D-25).
 */
public final class MicrometerCollabMetrics implements CollabMetrics {

    private final MeterRegistry registry;
    private final Timer ackLatency;
    private final DistributionSummary transforms;
    private final DistributionSummary commitBatch;
    private final Timer sessionLoad;
    private final DistributionSummary changeBytes;
    private final Map<String, Counter> nacks = new ConcurrentHashMap<>();

    public MicrometerCollabMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ackLatency = Timer.builder("collab.ack.latency")
                .description("묶음을 받은 때부터 ack 를 보낸 때까지")
                .publishPercentileHistogram()
                .register(registry);
        this.transforms = DistributionSummary.builder("collab.transform.count")
                .description("묶음 하나를 확정하려고 변환한 횟수")
                .publishPercentileHistogram()
                .register(registry);
        this.commitBatch = DistributionSummary.builder("collab.commit.batch.size")
                .description("한 번에 커밋한 묶음 수")
                .register(registry);
        this.sessionLoad = Timer.builder("collab.session.load")
                .description("세션을 올리려고 스냅샷과 로그를 읽은 시간")
                .publishPercentileHistogram()
                .register(registry);
        this.changeBytes = DistributionSummary.builder("collab.op.bytes")
                .description("받은 change 메시지의 바이트 수")
                .baseUnit("bytes")
                .register(registry);
    }

    @Override
    public void ackLatency(long nanos) {
        ackLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void transforms(long count) {
        transforms.record(count);
    }

    @Override
    public void nack(String reason) {
        nacks.computeIfAbsent(reason, r -> Counter.builder("collab.nack")
                        .description("거부한 묶음. 비율은 collab.ack.latency 의 횟수와 나눠 본다")
                        .tag("reason", r)
                        .register(registry))
                .increment();
    }

    @Override
    public void commitBatch(int size) {
        commitBatch.record(size);
    }

    @Override
    public void sessionLoad(long nanos) {
        sessionLoad.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void changeBytes(int bytes) {
        changeBytes.record(bytes);
    }
}
