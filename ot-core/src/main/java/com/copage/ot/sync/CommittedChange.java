package com.copage.ot.sync;

import com.copage.ot.op.Op;
import java.util.List;

/**
 * 서버가 확정한 연산 묶음 하나. 연산 로그의 한 행에 해당한다.
 *
 * @param rev 이 묶음이 만든 리비전
 * @param clientId 보낸 클라이언트(브라우저 탭)
 * @param clientSeq 그 클라이언트가 붙인 순번. (clientId, clientSeq) 쌍으로 같은 묶음의 재전송을 가려낸다
 * @param ops 앞선 확정 묶음들에 맞게 변환이 끝난 연산
 */
public record CommittedChange(long rev, String clientId, long clientSeq, List<Op> ops) {

    public CommittedChange {
        ops = List.copyOf(ops);
    }
}
