package com.copage.collab.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 버전 기록 저장소 포트 (docs/08-FEATURES.md 10.1, D-44). */
public interface VersionLog {

    enum Kind {
        AUTO,
        NAMED,
        RESTORE
    }

    /**
     * 버전 하나.
     *
     * @param editors 앞 버전 다음부터 이 리비전까지 편집한 사람(이름). 최대 몇 명까지만
     */
    record Version(UUID id, long rev, Kind kind, String name, UUID createdBy, Instant createdAt, List<String> editors) {
    }

    /** 최근 버전부터. beforeRev 가 있으면 그보다 작은 리비전만. */
    List<Version> list(UUID docId, Long beforeRev, int limit);

    /** 지금 문서의 마지막 리비전에 버전을 남긴다. 확정된 연산이 없으면 리비전 0 이다. */
    Version record(UUID docId, Kind kind, String name, UUID createdBy);

    /**
     * 자동 버전을 남긴다. 마지막 버전 뒤로 편집이 있었고, quiet 동안 조용했거나 마지막 버전에서 every 가 지난 문서마다 그 마지막
     * 리비전에 하나. 여러 노드가 함께 불러도 한 번만 남는다. @return 남긴 수
     */
    int recordAutoVersions(Duration quiet, Duration every);
}
