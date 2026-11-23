package com.copage.ot.sync;

import com.copage.ot.op.Op;
import java.util.List;

/**
 * 클라이언트가 서버로 보내는 편집 묶음.
 *
 * @param baseRev 이 묶음을 만들 때 클라이언트가 알고 있던 마지막 리비전
 */
public record Submission(String clientId, long clientSeq, long baseRev, List<Op> ops) {

    public Submission {
        ops = List.copyOf(ops);
    }
}
