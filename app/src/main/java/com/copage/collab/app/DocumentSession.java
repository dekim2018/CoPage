package com.copage.collab.app;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.sync.ServerDocument;
import com.copage.ot.sync.Submission;
import com.copage.ot.sync.SubmitResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 문서 하나의 세션 (docs/03-ARCHITECTURE.md 5.2). 누군가 문서를 열면 메모리에 올라오고, 마지막 연결이 끊기고
 * {@link Settings#unloadDelay()} 가 지나면 내려간다.
 *
 * <p>모든 상태는 세션의 직렬 실행기 안에서만 읽고 쓴다. 공개 메서드는 일을 실행기에 맡기고 바로 돌아온다.
 *
 * <p>받은 묶음은 바로 처리하지 않고 모아 둔다. 실행기가 돌 차례가 되면 그때까지 모인 묶음을 차례로 변환, 적용한 뒤 한 번에
 * 커밋하고, 커밋이 끝난 뒤에야 ack 와 remote 를 보낸다 (docs/06-DATA-MODEL.md 8.2, ADR 0005). 한가할 때는 묶음 하나가 바로
 * 커밋되고, 바쁠 때는 저절로 여러 개가 함께 커밋된다.
 */
public final class DocumentSession {

    private static final System.Logger LOG = System.getLogger(DocumentSession.class.getName());

    /** 세션 동작에 쓰는 값. */
    public record Settings(
            int maxConnections, long rebaseMaxGap, Duration rebaseMaxAge, Duration unloadDelay) {
    }

    /** 세션이 스스로 내려갈 때 등록처에서 빼 달라고 부른다. */
    public interface Unloader {
        void unloaded(DocumentSession session);
    }

    /** 일정 시간 뒤에 일을 실행한다. 테스트에서는 시간을 직접 돌린다. */
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private record Member(Connection connection, String clientId, PageRole role) {
    }

    private record Pending(Connection connection, Change change) {
    }

    private final UUID docId;
    private final DocumentStore store;
    private final ChangePolicy policy;
    private final Settings settings;
    private final Executor executor;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Unloader unloader;

    private ServerDocument server;
    private boolean closed;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private List<Pending> pending = new ArrayList<>();
    private boolean drainQueued;
    private Instant idleSince;

    public DocumentSession(
            UUID docId,
            DocumentStore store,
            ChangePolicy policy,
            Settings settings,
            Executor executor,
            Scheduler scheduler,
            Clock clock,
            Unloader unloader) {
        this.docId = docId;
        this.store = store;
        this.policy = policy;
        this.settings = settings;
        this.executor = new SerialExecutor(executor);
        this.scheduler = scheduler;
        this.clock = clock;
        this.unloader = unloader;
    }

    public UUID docId() {
        return docId;
    }

    /**
     * 연결을 세션에 넣고 welcome 을 보낸다. 세션이 이미 내려갔으면 retry 를 불러 새 세션으로 다시 시도하게 한다.
     *
     * @param role 티켓에 담긴 권한. 문서당 연결 한도를 넘으면 읽기로 낮춘다
     */
    public void join(Connection connection, Hello hello, PageRole role, Runnable retry) {
        executor.execute(() -> {
            if (closed) {
                retry.run();
                return;
            }
            try {
                ensureLoaded();
                doJoin(connection, hello, role);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "문서 " + docId + " 에 연결을 넣지 못했다", e);
                connection.close(1011, "서버 오류");
            }
        });
    }

    /** 편집 묶음을 받는다. 처리는 다음 커밋 차례에 한다. */
    public void submit(Connection connection, Change change) {
        executor.execute(() -> {
            if (closed || !members.containsKey(connection.id())) {
                // 내려간 세션에 남은 연결이다. 다시 붙으면 새 세션에서 이어진다.
                connection.close(CloseCodes.MOVED, "문서 세션이 다시 열린다");
                return;
            }
            pending.add(new Pending(connection, change));
            if (!drainQueued) {
                drainQueued = true;
                executor.execute(this::drain);
            }
        });
    }

    /** 연결이 끊겼다. 마지막 연결이었으면 일정 시간 뒤 세션을 내린다. */
    public void leave(Connection connection) {
        executor.execute(() -> {
            if (members.remove(connection.id()) != null && members.isEmpty() && !closed) {
                idleSince = clock.instant();
                scheduler.schedule(() -> executor.execute(this::unloadIfIdle), settings.unloadDelay());
            }
        });
    }

    private void ensureLoaded() {
        if (server == null) {
            DocumentStore.Snapshot snapshot = store.load(docId);
            server = new ServerDocument(snapshot.document(), snapshot.rev());
        }
    }

    private void doJoin(Connection connection, Hello hello, PageRole role) {
        long rev = server.rev();
        if (hello.lastRev() < 0 || (hello.resume() && hello.lastRev() > rev)) {
            connection.close(CloseCodes.PROTOCOL, "lastRev " + hello.lastRev() + " 은 이 문서에 없는 리비전이다");
            return;
        }
        if (members.size() >= settings.maxConnections()) {
            role = role.cap(PageRole.VIEW);
        }
        members.put(connection.id(), new Member(connection, hello.clientId(), role));
        idleSince = null;
        connection.send(welcome(hello, role));
    }

    /**
     * 처음 여는 연결에는 전체 문서를 보낸다. 이어 붙는 연결(resume)에는 놓친 묶음만 보낸다. 다만 리비전 차이가 너무 크거나,
     * 놓친 첫 묶음이 너무 오래전에 확정되었으면(그 뒤로 한 번도 확인하지 못했다는 뜻이다) 전체 문서와 rebase=false 를 보낸다
     * (7.5).
     */
    private ServerMessage.Welcome welcome(Hello hello, PageRole role) {
        long rev = server.rev();
        long lastRev = hello.lastRev();
        if (!hello.resume()) {
            return new ServerMessage.Welcome(rev, role, true, server.document(), null);
        }
        if (rev - lastRev > settings.rebaseMaxGap()) {
            return new ServerMessage.Welcome(rev, role, false, server.document(), null);
        }
        List<StoredChange> missed = store.changesSince(docId, lastRev);
        if (!missed.isEmpty() && missed.get(0).committedAt().plus(settings.rebaseMaxAge()).isBefore(clock.instant())) {
            return new ServerMessage.Welcome(rev, role, false, server.document(), null);
        }
        return new ServerMessage.Welcome(rev, role, true, null, missed);
    }

    /** 모인 묶음을 처리하고 한 번에 커밋한 뒤 결과를 보낸다. */
    private void drain() {
        drainQueued = false;
        if (closed) {
            return;
        }
        List<Pending> batch = pending;
        pending = new ArrayList<>();
        List<Runnable> replies = new ArrayList<>();
        List<StoredChange> commits = new ArrayList<>();
        Instant now = clock.instant();
        for (Pending item : batch) {
            process(item, now, commits, replies);
        }
        if (!commits.isEmpty()) {
            try {
                store.append(docId, commits);
            } catch (RuntimeException e) {
                // 메모리의 문서가 저장소보다 앞서 버렸다. 세션을 버리고, 클라이언트는 재접속해서 저장소 기준으로 다시 맞춘다
                // (docs/03-ARCHITECTURE.md 5.3).
                LOG.log(System.Logger.Level.ERROR, "문서 " + docId + " 커밋 실패. 세션을 버린다", e);
                discard();
                return;
            }
        }
        replies.forEach(Runnable::run);
    }

    private void process(Pending item, Instant now, List<StoredChange> commits, List<Runnable> replies) {
        Connection sender = item.connection();
        Change change = item.change();
        Member member = members.get(sender.id());
        long seq = change.clientSeq();
        if (member == null) {
            // 보낸 뒤 끊긴 연결이다. 처리하지 않으면 재접속한 클라이언트가 같은 순번으로 다시 보낸다.
            return;
        }
        Optional<String> refused = change.ops().isEmpty()
                ? Optional.of("빈 묶음")
                : policy.check(change.ops(), member.role());
        if (refused.isPresent()) {
            long rev = server.rev();
            replies.add(() -> sender.send(new ServerMessage.Nack(seq, refused.get(), rev)));
            return;
        }
        // 세션이 다시 올라오면 ServerDocument 는 예전 순번을 모른다. 저장소에서 한 번 더 확인한다 (7.5 의 4번).
        OptionalLong earlier = store.committedRev(docId, member.clientId(), seq);
        if (earlier.isPresent()) {
            long rev = earlier.getAsLong();
            replies.add(() -> sender.send(new ServerMessage.Ack(seq, rev)));
            return;
        }
        Submission submission = new Submission(member.clientId(), seq, change.baseRev(), change.ops());
        switch (server.submit(submission, policy::checkSize)) {
            case SubmitResult.Accepted accepted -> {
                StoredChange stored = new StoredChange(
                        accepted.change().rev(), sender.userId(), member.clientId(), seq, accepted.change().ops(), now);
                commits.add(stored);
                replies.add(() -> broadcast(sender, stored));
            }
            case SubmitResult.Duplicate duplicate ->
                    replies.add(() -> sender.send(new ServerMessage.Ack(seq, duplicate.rev())));
            case SubmitResult.Rejected rejected -> {
                long rev = server.rev();
                replies.add(() -> sender.send(new ServerMessage.Nack(seq, rejected.reason(), rev)));
            }
        }
    }

    /** 보낸 연결에는 ack, 나머지에는 remote. 같은 클라이언트가 다른 연결로 다시 붙어 있으면 그 연결도 remote 를 받는다. */
    private void broadcast(Connection sender, StoredChange change) {
        for (Member member : members.values()) {
            if (member.connection().id().equals(sender.id())) {
                member.connection().send(new ServerMessage.Ack(change.clientSeq(), change.rev()));
            } else {
                member.connection().send(new ServerMessage.Remote(change));
            }
        }
    }

    private void unloadIfIdle() {
        if (closed || !members.isEmpty() || !pending.isEmpty() || idleSince == null) {
            return;
        }
        if (idleSince.plus(settings.unloadDelay()).isAfter(clock.instant())) {
            return;
        }
        closed = true;
        unloader.unloaded(this);
    }

    private void discard() {
        closed = true;
        pending.clear();
        for (Member member : members.values()) {
            member.connection().close(CloseCodes.MOVED, "문서 세션을 다시 연다");
        }
        members.clear();
        unloader.unloaded(this);
    }
}
