package com.copage.ot.sync;

import com.copage.ot.TransformConflictException;
import com.copage.ot.Transformer;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 서버가 문서 하나에 대해 들고 있는 상태와, 편집 묶음을 받아 확정 순서에 넣는 규칙.
 *
 * <p>저장소와 네트워크를 모르는 순수 로직이다. 협업 서버의 문서 세션은 이 클래스를 감싸서 DB 커밋과 전송을 붙인다.
 * 스레드 안전하지 않으므로 문서마다 하나의 실행 흐름에서만 호출해야 한다.
 */
public final class ServerDocument {

    private Document document;
    private final long firstRev;
    private final List<CommittedChange> history = new ArrayList<>();
    private final Map<String, Map<Long, Long>> seenSeqs = new HashMap<>();

    /**
     * @param document 스냅샷에서 읽은 문서
     * @param rev 그 스냅샷의 리비전. 새 문서는 0
     */
    public ServerDocument(Document document, long rev) {
        this.document = document;
        this.firstRev = rev;
    }

    public Document document() {
        return document;
    }

    public long rev() {
        return firstRev + history.size();
    }

    /** fromRev 다음부터 지금까지 확정된 묶음. 재접속한 클라이언트에게 보낸다. */
    public List<CommittedChange> changesSince(long fromRev) {
        if (fromRev < firstRev || fromRev > rev()) {
            throw new IllegalArgumentException("리비전 " + fromRev + " 이후의 기록을 갖고 있지 않다");
        }
        return List.copyOf(history.subList((int) (fromRev - firstRev), history.size()));
    }

    /** 편집 묶음을 받는다. 받아들이면 문서와 리비전이 바뀐다. */
    public SubmitResult submit(Submission submission) {
        return submit(submission, document -> {
        });
    }

    /**
     * 편집 묶음을 받되, 받아들이기 직전에 결과 문서를 validator 로 검사한다. validator 가
     * {@link InvalidOperationException} 을 던지면 묶음을 거부하고 상태는 그대로 둔다.
     *
     * <p>문서 크기 한도처럼 변환하고 적용한 결과를 봐야 알 수 있는 협업 서버 쪽 규칙을 걸 때 쓴다.
     */
    public SubmitResult submit(Submission submission, Consumer<Document> validator) {
        Long committedRev = seenSeqs
                .getOrDefault(submission.clientId(), Map.of())
                .get(submission.clientSeq());
        if (committedRev != null) {
            return new SubmitResult.Duplicate(committedRev);
        }
        if (submission.baseRev() < firstRev || submission.baseRev() > rev()) {
            return new SubmitResult.Rejected("기준 리비전 " + submission.baseRev() + " 을 처리할 수 없다");
        }
        if (submission.ops().isEmpty()) {
            return new SubmitResult.Rejected("빈 묶음");
        }
        List<Op> ops = submission.ops();
        Document next;
        try {
            // 클라이언트가 보지 못한 확정 묶음들에 차례로 맞춘다. 받은 묶음이 항상 나중에 확정되는 쪽이다.
            for (CommittedChange earlier : changesSince(submission.baseRev())) {
                ops = Transformer.transform(ops, earlier.ops(), true).a();
            }
            next = document.apply(ops);
            validator.accept(next);
        } catch (TransformConflictException | InvalidOperationException e) {
            return new SubmitResult.Rejected(e.getMessage());
        }
        if (ops.isEmpty()) {
            // 변환하고 나니 할 일이 없어졌다(예: 두 사람이 같은 합치기를 했다). 리비전을 올리지 않고 확인만 해 준다.
            return new SubmitResult.Duplicate(rev());
        }
        document = next;
        CommittedChange change =
                new CommittedChange(rev() + 1, submission.clientId(), submission.clientSeq(), ops);
        history.add(change);
        seenSeqs.computeIfAbsent(submission.clientId(), id -> new HashMap<>())
                .put(submission.clientSeq(), change.rev());
        return new SubmitResult.Accepted(change);
    }
}
