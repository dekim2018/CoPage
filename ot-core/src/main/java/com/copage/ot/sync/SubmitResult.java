package com.copage.ot.sync;

/** 서버가 편집 묶음을 받은 결과. */
public sealed interface SubmitResult permits SubmitResult.Accepted, SubmitResult.Duplicate, SubmitResult.Rejected,
        SubmitResult.Stale {

    /** 새 리비전으로 확정되었다. 보낸 사람에게 ack, 나머지에게 change 를 보낸다. */
    record Accepted(CommittedChange change) implements SubmitResult {
    }

    /** 이미 확정된 묶음이 다시 왔다. 문서는 바뀌지 않고, 처음 확정된 리비전으로 ack 를 다시 보낸다. */
    record Duplicate(long rev) implements SubmitResult {
    }

    /** 변환할 수 없거나 적용할 수 없어 거부했다. 보낸 사람에게 nack 를 보낸다. */
    record Rejected(String reason) implements SubmitResult {
    }

    /**
     * 기준 리비전이 기록 창보다 오래되어 변환할 수 없다. 묶음 자체는 틀리지 않았으므로 nack 로 버리게 하지 않는다. 협업 서버는
     * 연결을 닫아 클라이언트가 재접속해서 다시 맞추게 한다(docs/05-PROTOCOL.md 7.5).
     *
     * @param oldestBaseRev 지금 받을 수 있는 가장 오래된 기준 리비전
     */
    record Stale(long oldestBaseRev) implements SubmitResult {
    }
}
