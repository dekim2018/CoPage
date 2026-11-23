package com.copage.ot.sync;

/** 서버가 편집 묶음을 받은 결과. */
public sealed interface SubmitResult permits SubmitResult.Accepted, SubmitResult.Duplicate, SubmitResult.Rejected {

    /** 새 리비전으로 확정되었다. 보낸 사람에게 ack, 나머지에게 change 를 보낸다. */
    record Accepted(CommittedChange change) implements SubmitResult {
    }

    /** 이미 확정된 묶음이 다시 왔다. 문서는 바뀌지 않고, 처음 확정된 리비전으로 ack 를 다시 보낸다. */
    record Duplicate(long rev) implements SubmitResult {
    }

    /** 변환할 수 없거나 적용할 수 없어 거부했다. 보낸 사람에게 nack 를 보낸다. */
    record Rejected(String reason) implements SubmitResult {
    }
}
