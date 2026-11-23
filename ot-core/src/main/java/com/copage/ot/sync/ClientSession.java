package com.copage.ot.sync;

import com.copage.ot.Changes;
import com.copage.ot.TransformConflictException;
import com.copage.ot.Transformer;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import java.util.List;
import java.util.Optional;

/**
 * 클라이언트 한 개(브라우저 탭 하나)가 문서를 서버와 맞추는 상태 기계.
 *
 * <p>세 가지를 들고 있다.
 *
 * <ul>
 *   <li>확정 문서: 서버가 확정한 묶음만 순서대로 적용한 문서. 항상 서버의 어느 리비전과 정확히 같다.
 *   <li>inflight: 서버로 보내고 답을 기다리는 묶음. 한 번에 하나만 보낸다.
 *   <li>buffer: 아직 보내지 않은 묶음.
 * </ul>
 *
 * <p>화면에 보이는 문서는 확정 문서에 inflight 와 buffer 를 차례로 적용한 결과다. 다른 사람의 묶음이 오면 확정 문서에
 * 적용하고, inflight 와 buffer 를 그 묶음에 맞게 변환한 뒤 화면 문서를 다시 계산한다. inflight 를 변환하는 순서와 함수가
 * 서버와 같으므로, 서버가 확정한 내 묶음과 여기서 들고 있는 inflight 는 항상 같은 값이 된다.
 *
 * <p>브라우저의 TypeScript 구현도 이 클래스와 같은 규칙을 따른다. 부하 도구는 이 클래스를 그대로 쓴다.
 */
public final class ClientSession {

    private final String clientId;
    private Document confirmed;
    private long confirmedRev;
    private long nextSeq = 1;

    private List<Op> inflight;
    private long inflightSeq;
    /** inflight 가 화면 문서에 반영되어 있으면 true. false 면 buffer 는 확정 문서 바로 위에 쌓인 것이다. */
    private boolean inflightInView;
    /** 변환 충돌이 나서 서버가 반드시 거부할 inflight 면 true. */
    private boolean inflightDead;

    private List<Op> buffer = List.of();
    private Document view;

    public ClientSession(String clientId, Document document, long rev) {
        this.clientId = clientId;
        this.confirmed = document;
        this.confirmedRev = rev;
        this.view = document;
    }

    public String clientId() {
        return clientId;
    }

    /** 화면에 보이는 문서. 내 편집이 서버 확인 전에 먼저 반영되어 있다. */
    public Document view() {
        return view;
    }

    public Document confirmed() {
        return confirmed;
    }

    public long confirmedRev() {
        return confirmedRev;
    }

    /** 서버 확인을 기다리거나 아직 보내지 않은 편집이 있으면 true. */
    public boolean hasPending() {
        return inflight != null || !buffer.isEmpty();
    }

    /**
     * 내 편집을 화면 문서에 바로 적용하고 buffer 에 쌓는다.
     *
     * @throws InvalidOperationException 지금 화면 문서에 적용할 수 없는 편집일 때. 상태는 바뀌지 않는다
     */
    public void edit(List<Op> ops) {
        view = view.apply(ops);
        buffer = Changes.append(buffer, ops);
    }

    /** 보낼 묶음이 있고 답을 기다리는 묶음이 없으면, buffer 를 inflight 로 옮기고 보낼 내용을 돌려준다. */
    public Optional<Submission> poll() {
        if (inflight != null || buffer.isEmpty()) {
            return Optional.empty();
        }
        inflight = buffer;
        inflightSeq = nextSeq++;
        inflightInView = true;
        inflightDead = false;
        buffer = List.of();
        return Optional.of(new Submission(clientId, inflightSeq, confirmedRev, inflight));
    }

    /** 서버가 확정한 묶음을 받는다. 내 inflight 가 확정된 것이면 ack 로, 아니면 다른 사람의 편집으로 처리한다. */
    public void onChange(CommittedChange change) {
        if (change.rev() != confirmedRev + 1) {
            throw new IllegalStateException("리비전 " + (confirmedRev + 1) + " 을 기다리는데 " + change.rev() + " 이 왔다");
        }
        if (inflight != null && clientId.equals(change.clientId()) && change.clientSeq() == inflightSeq) {
            onAck(change.clientSeq(), change.rev());
        } else {
            onRemote(change.ops());
        }
    }

    /** 내 inflight 가 rev 로 확정되었다. 기다리던 순번이 아니면 무시한다. */
    public void onAck(long clientSeq, long rev) {
        if (isStale(clientSeq)) {
            return;
        }
        if (inflightDead) {
            throw new IllegalStateException("변환 충돌이 난 묶음에 ack 가 왔다. 서버와 변환 규칙이 다르다");
        }
        List<Op> ops = inflight;
        if (!ops.isEmpty()) {
            if (rev != confirmedRev + 1) {
                throw new IllegalStateException("ack 리비전이 맞지 않는다: 기대 " + (confirmedRev + 1) + ", 실제 " + rev);
            }
            confirmed = confirmed.apply(ops);
            confirmedRev = rev;
        }
        inflight = null;
        if (!inflightInView) {
            // 화면에서 빼 두었던 묶음이 확정되었다. buffer 입장에서는 다른 사람의 편집이 온 것과 같다.
            buffer = transformOrDrop(buffer, ops);
            rebuildView();
        }
    }

    /** 내 inflight 가 거부되었다. 그 위에 쌓은 buffer 도 함께 버리고 화면을 확정 문서로 되돌린다. */
    public void onNack(long clientSeq) {
        if (isStale(clientSeq)) {
            return;
        }
        inflight = null;
        if (inflightInView) {
            buffer = List.of();
        }
        rebuildView();
    }

    /**
     * 연결이 끊겼다 돌아왔을 때 호출한다. 놓친 묶음을 반영하고, 다시 보내야 할 묶음이 있으면 돌려준다.
     *
     * <p>끊기기 전에 보낸 inflight 가 서버에 들어갔다면 missed 안에 내 clientId 와 순번으로 들어 있어 ack 로 처리된다.
     * 들어가지 않았다면 같은 순번으로 다시 보낸다. 두 번 도착해도 서버가 순번으로 걸러 낸다.
     */
    public Optional<Submission> resync(List<CommittedChange> missed) {
        for (CommittedChange change : missed) {
            onChange(change);
        }
        if (inflight == null) {
            return poll();
        }
        if (inflightDead || inflight.isEmpty()) {
            // 보내도 거부될 묶음이거나, 변환하고 나니 할 일이 없어진 묶음이다.
            inflight = null;
            rebuildView();
            return poll();
        }
        return Optional.of(new Submission(clientId, inflightSeq, confirmedRev, inflight));
    }

    private void onRemote(List<Op> ops) {
        confirmed = confirmed.apply(ops);
        confirmedRev++;

        List<Op> overInflight = ops;
        boolean wasInView = inflight != null && inflightInView;
        if (inflight != null && !inflightDead) {
            try {
                Transformer.Pair pair = Transformer.transform(inflight, ops, true);
                inflight = pair.a();
                overInflight = pair.b();
            } catch (TransformConflictException e) {
                inflightDead = true;
            }
        }
        if (wasInView && inflightDead) {
            // inflight 가 거부될 것이 확실하다. 그 위에 쌓은 buffer 는 의미를 잃었으므로 버린다.
            inflightInView = false;
            buffer = List.of();
        } else if (wasInView) {
            buffer = transformOrDrop(buffer, overInflight);
        } else {
            buffer = transformOrDrop(buffer, ops);
        }
        rebuildView();
    }

    private void rebuildView() {
        Document base = confirmed;
        if (inflight != null && inflightInView) {
            try {
                base = confirmed.apply(inflight);
            } catch (InvalidOperationException e) {
                // 지금 확정 문서에는 적용할 수 없다. 서버의 판정이 올 때까지 화면에서 빼 둔다.
                inflightInView = false;
                buffer = List.of();
            }
        }
        try {
            view = base.apply(buffer);
        } catch (InvalidOperationException e) {
            buffer = List.of();
            view = base;
        }
    }

    private static List<Op> transformOrDrop(List<Op> pending, List<Op> against) {
        try {
            return Transformer.transform(pending, against, true).a();
        } catch (TransformConflictException e) {
            return List.of();
        }
    }

    /**
     * 지금 기다리는 묶음에 대한 답이 아니면 true. 재접속 뒤에 예전 연결로 보낸 묶음의 답이 늦게 올 수 있고, 그런 답은
     * 이미 다른 경로로 처리했으므로 무시한다.
     */
    private boolean isStale(long clientSeq) {
        return inflight == null || clientSeq != inflightSeq;
    }
}
