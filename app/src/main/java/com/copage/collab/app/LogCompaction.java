package com.copage.collab.app;

import com.copage.ot.model.Document;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** 연산 로그 정리의 저장소 포트 (D-52). */
public interface LogCompaction {

    record Version(UUID id, long rev, VersionLog.Kind kind, Instant createdAt) {
    }

    /** cutoff 보다 오래된 연산이 남아 있는 문서. */
    List<UUID> docsWithOpsBefore(Instant cutoff, int limit);

    /** cutoff 보다 먼저 확정된 마지막 리비전. */
    OptionalLong lastRevBefore(UUID docId, Instant cutoff);

    /** throughRev 이하를 가리키는 버전. */
    List<Version> versionsThrough(UUID docId, long throughRev);

    /** 이미 스냅샷이 있는 리비전. */
    Set<Long> snapshotRevs(UUID docId);

    /**
     * 한 트랜잭션에서: pins 의 문서를 고정 스냅샷으로 남기고(pinExisting 은 이미 있는 스냅샷을 고정으로 바꾼다), dropVersions 를
     * 지우고, throughRev 이하의 연산을 지우고, 남은 버전이 가리키지 않는 throughRev 앞의 스냅샷을 지우고, 정리 지점을 적는다.
     */
    void compact(UUID docId, long throughRev, Map<Long, Document> pins, Set<Long> pinExisting, Set<UUID> dropVersions,
            Instant now);
}
