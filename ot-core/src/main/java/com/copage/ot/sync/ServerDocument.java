package com.copage.ot.sync;

import com.copage.ot.TransformConflictException;
import com.copage.ot.Transformer;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 서버가 문서 하나에 대해 들고 있는 상태와, 편집 묶음을 받아 확정 순서에 넣는 규칙.
 *
 * <p>저장소와 네트워크를 모르는 순수 로직이다. 협업 서버의 문서 세션은 이 클래스를 감싸서 DB 커밋과 전송을 붙인다.
 * 스레드 안전하지 않으므로 문서마다 하나의 실행 흐름에서만 호출해야 한다.
 *
 * <p>확정 기록은 최근 maxHistory 개만 든다. 받은 묶음을 변환하려면 그 기준 리비전 뒤의 기록이 있어야 하므로, 창보다 오래된
 * 기준 리비전의 묶음은 {@link SubmitResult.Stale} 로 돌려준다. 그런 클라이언트는 재접속해 맞춘다(docs/05-PROTOCOL.md 7.5). 창이 없으면 세션이 열려 있는 동안
 * 기록과 순번 맵이 끝없이 쌓이고, 아주 오래된 기준 리비전의 묶음이 기록 전체에 대해 변환되어 실행 흐름을 오래 잡는다. 창 밖으로
 * 나간 묶음의 재전송은 이 클래스가 알아보지 못하므로, 협업 서버가 저장소의 (clientId, clientSeq) 유일 제약으로 먼저 거른다.
 */
public final class ServerDocument {

    private Document document;
    private long rev;
    private final int maxHistory;
    private final Deque<CommittedChange> history = new ArrayDeque<>();
    private final Map<String, Map<Long, Long>> seenSeqs = new HashMap<>();

    /**
     * 기록 창 없이 만든다. 확정 기록을 모두 든다. 테스트와 시뮬레이션용이다.
     *
     * @param document 스냅샷에서 읽은 문서
     * @param rev 그 스냅샷의 리비전. 새 문서는 0
     */
    public ServerDocument(Document document, long rev) {
        this(document, rev, Integer.MAX_VALUE);
    }

    /** @param maxHistory 들고 있을 최근 확정 기록 수. 1 이상 */
    public ServerDocument(Document document, long rev, int maxHistory) {
        if (maxHistory < 1) {
            throw new IllegalArgumentException("기록 창은 1 이상이어야 한다");
        }
        this.document = document;
        this.rev = rev;
        this.maxHistory = maxHistory;
    }

    public Document document() {
        return document;
    }

    public long rev() {
        return rev;
    }

    /** 변환할 수 있는 가장 오래된 기준 리비전. 이보다 오래된 기준 리비전의 묶음은 Stale 로 돌려준다. */
    public long oldestBaseRev() {
        return rev - history.size();
    }

    /** 지금 들고 있는 확정 기록 수. 최대 maxHistory 다. */
    public int historySize() {
        return history.size();
    }

    /** fromRev 다음부터 지금까지 확정된 묶음. fromRev 는 oldestBaseRev() 이상이어야 한다. */
    public List<CommittedChange> changesSince(long fromRev) {
        if (fromRev < oldestBaseRev() || fromRev > rev) {
            throw new IllegalArgumentException("리비전 " + fromRev + " 이후의 기록을 갖고 있지 않다");
        }
        // 보통 fromRev 는 rev 에 가깝다. 창 전체를 돌지 않도록 뒤에서 필요한 만큼만 모은다.
        int count = (int) (rev - fromRev);
        CommittedChange[] out = new CommittedChange[count];
        Iterator<CommittedChange> newestFirst = history.descendingIterator();
        for (int i = count - 1; i >= 0; i--) {
            out[i] = newestFirst.next();
        }
        return List.of(out);
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
        if (submission.baseRev() > rev) {
            return new SubmitResult.Rejected("기준 리비전 " + submission.baseRev() + " 은 아직 없다");
        }
        if (submission.baseRev() < oldestBaseRev()) {
            return new SubmitResult.Stale(oldestBaseRev());
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
        rev++;
        CommittedChange change = new CommittedChange(rev, submission.clientId(), submission.clientSeq(), ops);
        history.addLast(change);
        seenSeqs.computeIfAbsent(submission.clientId(), id -> new HashMap<>())
                .put(submission.clientSeq(), change.rev());
        while (history.size() > maxHistory) {
            forget(history.removeFirst());
        }
        return new SubmitResult.Accepted(change);
    }

    /** 창 밖으로 나간 묶음의 순번을 잊는다. 그 클라이언트의 순번이 더 없으면 맵에서도 뺀다. */
    private void forget(CommittedChange change) {
        Map<Long, Long> seqs = seenSeqs.get(change.clientId());
        if (seqs != null) {
            seqs.remove(change.clientSeq());
            if (seqs.isEmpty()) {
                seenSeqs.remove(change.clientId());
            }
        }
    }
}
