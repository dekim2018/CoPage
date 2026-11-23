package com.copage.collab.domain;

import com.copage.ot.op.Op;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 연산 로그의 한 행 (docs/06-DATA-MODEL.md 8.1 의 doc_ops). 서버가 확정한 묶음 하나다.
 *
 * @param ops 앞선 확정 묶음들에 맞게 변환이 끝난 연산
 */
public record StoredChange(long rev, UUID userId, String clientId, long clientSeq, List<Op> ops, Instant committedAt) {

    public StoredChange {
        ops = List.copyOf(ops);
    }
}
