package com.copage.collab.app;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.Cursor;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.Peer;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.sync.ServerDocument;
import com.copage.ot.sync.Submission;
import com.copage.ot.sync.SubmitResult;
import com.copage.ot.text.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * 문서 하나의 세션 (docs/03-ARCHITECTURE.md 5.2). 누군가 문서를 열면 메모리에 올라오고, 마지막 연결이 끊기고
 * {@link Settings#unloadDelay()} 가 지나면 내려간다.
 *
 * <p>모든 상태는 세션의 직렬 실행기 안에서만 읽고 쓴다. 공개 메서드는 일을 실행기에 맡기고 바로 돌아온다.
 *
 * <p>받은 묶음은 바로 처리하지 않고 모아 둔다. 실행기가 돌 차례가 되면 그때까지 모인 묶음을 차례로 변환, 적용한 뒤 한 번에
 * 커밋하고, 커밋이 끝난 뒤에야 ack 와 remote 를 보낸다 (docs/06-DATA-MODEL.md 8.2, ADR 0005). 한가할 때는 묶음 하나가 바로
 * 커밋되고, 바쁠 때는 저절로 여러 개가 함께 커밋된다.
 *
 * <p>프레즌스(커서와 입장, 퇴장)는 저장하지 않는다. 연결마다 마지막 커서 하나만 기억해 다른 연결에 그대로 넘기고, 새로 들어온
 * 연결에는 welcome 의 peers 로 알려 준다 (docs/05-PROTOCOL.md 7.6).
 *
 * <p>마지막 스냅샷 이후 확정된 묶음이 {@link Settings#snapshotInterval()} 개 쌓이면, 그리고 세션을 내릴 때 스냅샷을 남긴다.
 * 문서는 바뀌지 않는 값이라 그대로 넘겨 별도 스레드에서 저장하고, 편집 처리는 기다리지 않는다 (docs/06-DATA-MODEL.md
 * 8.3). 스냅샷 저장이 실패해도 연산 로그가 원본이라 잃는 것은 없다.
 */
public final class DocumentSession {

    private static final System.Logger LOG = System.getLogger(DocumentSession.class.getName());

    /** 세션 동작에 쓰는 값. */
    public record Settings(
            int maxConnections,
            long rebaseMaxGap,
            Duration rebaseMaxAge,
            Duration unloadDelay,
            long snapshotInterval,
            int historyWindow) {

        public Settings {
            if (historyWindow < 1) {
                throw new IllegalArgumentException("기록 창(history-window)은 1 이상이어야 한다: " + historyWindow);
            }
        }
    }

    /** 세션이 스스로 내려갈 때 등록처에서 빼 달라고 부른다. */
    public interface Unloader {
        void unloaded(DocumentSession session);
    }

    /** 일정 시간 뒤에 일을 실행한다. 테스트에서는 시간을 직접 돌린다. */
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private static final class Member {
        final Connection connection;
        final String clientId;
        /** 문서당 연결 한도를 넘어 읽기로 낮춰 받았는지. 권한을 다시 계산해도 읽기 위로는 올리지 않는다. */
        final boolean capped;
        final String name;
        PageRole role;
        Cursor cursor;
        /**
         * 이 연결에서 다음 묶음의 순번이 넘어야 하는 값. 음수면 아직 저장소의 순번 기록을 읽지 않았다. 한 연결의 순번은 늘기만
         * 하므로 첫 묶음에서 저장소를 한 번 읽고 나면 그 뒤로는 겹칠 수 없다(PERF-2).
         */
        long seqFloor = -1;

        Member(Connection connection, String clientId, PageRole role, boolean capped, String name) {
            this.connection = connection;
            this.clientId = clientId;
            this.role = role;
            this.capped = capped;
            this.name = name;
        }

        Connection connection() {
            return connection;
        }

        String clientId() {
            return clientId;
        }

        PageRole role() {
            return role;
        }

        Peer peer() {
            String color = connection.color() != null ? connection.color() : Peer.colorOf(connection.userId());
            return new Peer(clientId, connection.userId(), name, color, cursor);
        }
    }

    /** @param receivedNanos 받은 때. ack 지연을 잰다 */
    private record Pending(Connection connection, Change change, long receivedNanos) {
    }

    private final UUID docId;
    private final DocumentStore store;
    private final ChangePolicy policy;
    private final Settings settings;
    private final Executor executor;
    private final Executor background;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Unloader unloader;
    /** 멘션 서식의 이름 (6.1). */
    private static final String MENTION_MARK = "mention";

    private final MentionSink mentions;
    /** 검색 본문을 알리는 간격. 편집이 이어지는 동안에는 이 간격마다 한 번 알린다 (D-47). */
    private static final Duration BODY_INTERVAL = Duration.ofSeconds(5);

    private final BodySink bodies;
    /** 마지막으로 알린 뒤 커밋이 있었는지. 실행기 스레드에서만 읽고 쓴다. */
    private boolean bodyDirty;
    private boolean bodyScheduled;
    private final TitleSink titles;
    private final CollabMetrics metrics;
    /** 마지막으로 알린 제목. 같은 제목을 다시 쓰지 않는다. */
    private String lastTitle;

    private ServerDocument server;
    private long lastSnapshotRev;
    private boolean closed;
    /** 저장소보다 앞선 채 버렸는지. 버린 세션의 본문은 검색에 알리지 않는다. */
    private boolean discarded;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private List<Pending> pending = new ArrayList<>();
    private boolean drainQueued;
    private Instant idleSince;
    /** 다시 계산을 시작한 순번. 실행 흐름에서만 고친다. */
    private long reauthorizeSeq;
    /** 권한 주체(사용자, 공유 링크)마다 마지막으로 적용한 다시 계산의 순번. 늦게 끝난 낡은 계산이 새 결과를 덮지 않게 한다. */
    private final Map<Principal, Long> appliedSeq = new HashMap<>();
    /**
     * 끝나지 않은 다시 계산의 수. 0 일 때만 연결이 없는 주체의 순번을 지운다. 계산 중에 지우면 늦게 끝난 낡은 결과가 다시 붙은
     * 같은 주체에 적용될 수 있다. 공유 링크의 비로그인 방문자는 연결마다 새 주체라 지우지 않으면 끝없이 쌓인다 (D-41).
     */
    private int reauthorizeInFlight;

    public DocumentSession(
            UUID docId,
            DocumentStore store,
            ChangePolicy policy,
            Settings settings,
            Executor executor,
            Executor background,
            Scheduler scheduler,
            Clock clock,
            TitleSink titles,
            CollabMetrics metrics,
            MentionSink mentions,
            BodySink bodies,
            Unloader unloader) {
        this.docId = docId;
        this.mentions = mentions;
        this.bodies = bodies;
        this.titles = titles;
        this.metrics = metrics;
        this.store = store;
        this.policy = policy;
        this.settings = settings;
        this.executor = new SerialExecutor(executor);
        this.background = background;
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
     * @param name 접속자 목록과 남의 커서에 보일 이름
     */
    public void join(Connection connection, Hello hello, PageRole role, String name, Runnable retry) {
        executor.execute(() -> {
            if (closed) {
                retry.run();
                return;
            }
            try {
                ensureLoaded();
                doJoin(connection, hello, role, name);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "문서 " + docId + " 에 연결을 넣지 못했다", e);
                connection.close(1011, "서버 오류");
            }
            // 세션을 연 연결이 멤버가 되지 못하면 이 연결의 leave 는 아무도 빼지 않는다. 여기서 내리기를 예약해야 세션이
            // 남지 않는다.
            scheduleUnloadIfEmpty();
        });
    }

    /** 편집 묶음을 받는다. 처리는 다음 커밋 차례에 한다. */
    public void submit(Connection connection, Change change) {
        long received = System.nanoTime();
        executor.execute(() -> {
            if (closed || !members.containsKey(connection.id())) {
                // 내려간 세션에 남은 연결이다. 다시 붙으면 새 세션에서 이어진다.
                connection.close(CloseCodes.MOVED, "문서 세션이 다시 열린다");
                return;
            }
            pending.add(new Pending(connection, change, received));
            if (!drainQueued) {
                drainQueued = true;
                executor.execute(this::drain);
            }
        });
    }

    /** 커서가 움직였다. 기억해 두고 다른 연결에 넘긴다. 읽기 권한도 보낼 수 있다 (docs/07-SECURITY.md 9.5). */
    public void presence(Connection connection, Cursor cursor) {
        executor.execute(() -> {
            Member member = members.get(connection.id());
            if (closed || member == null) {
                return;
            }
            member.cursor = cursor;
            tellOthers(member, new ServerMessage.Presence(member.peer(), false));
        });
    }

    /**
     * 이 문서 권한을 다시 계산한다. 권한이 없으면 빈 값. 공유 링크로 들어온 연결은 링크가 살아 있는지도 본다. 저장소를 읽으므로
     * 세션 실행 흐름 밖에서 부른다.
     */
    public interface RoleResolver {
        Optional<PageRole> roleOf(Principal principal, UUID docId);
    }

    /**
     * 권한이 바뀐 사용자의 연결에 새 등급을 적용한다 (docs/07-SECURITY.md 9.4, S-5). 내려가거나 올라가면 role 을 보내고, 잃었으면
     * 4003 으로 닫는다. 그 뒤에 처리하는 묶음은 새 등급으로 검사한다.
     *
     * <p>세션 실행 흐름에서 대상 연결을 모으고, 등급 계산(저장소 읽기)은 background 에서 하고, 적용은 다시 실행 흐름에서 한다.
     * 실행 흐름을 저장소 읽기로 막지 않기 위해서다. 계산하는 사이 나간 연결은 건너뛴다.
     *
     * <p>계산은 나란히 돌 수 있어 끝나는 순서가 시작 순서와 다를 수 있다. 그래서 시작할 때 순번을 붙이고, 그 사용자에게 더 나중
     * 순번을 이미 적용했으면 버린다. 나중 순번의 계산은 앞 변경이 커밋된 뒤에 시작했으므로 더 새로운 상태를 읽는다.
     *
     * @param users 다시 계산할 사용자
     */
    public void reauthorize(Predicate<UUID> users, RoleResolver resolver) {
        executor.execute(() -> {
            if (closed) {
                return;
            }
            List<Principal> targets = members.values().stream()
                    .map(member -> member.connection().principal())
                    .filter(principal -> users.test(principal.userId()))
                    .distinct()
                    .toList();
            if (targets.isEmpty()) {
                return;
            }
            long seq = ++reauthorizeSeq;
            reauthorizeInFlight++;
            background.execute(() -> {
                Map<Principal, Optional<PageRole>> roles = new HashMap<>();
                try {
                    for (Principal principal : targets) {
                        roles.put(principal, resolver.roleOf(principal, docId));
                    }
                } catch (RuntimeException e) {
                    // 계산하지 못하면 바꾸지 않는다. 다음 재접속 때 새 티켓이 바른 등급을 준다.
                    LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 권한을 다시 계산하지 못했다", e);
                    executor.execute(this::finishReauthorize);
                    return;
                }
                executor.execute(() -> applyRoles(seq, roles));
            });
        });
    }

    private void finishReauthorize() {
        reauthorizeInFlight--;
        pruneAppliedSeq();
    }

    /** 끝나지 않은 다시 계산이 없으면 연결이 하나도 없는 주체의 순번을 지운다. */
    private void pruneAppliedSeq() {
        if (reauthorizeInFlight > 0) {
            return;
        }
        Set<Principal> connected = new HashSet<>();
        members.values().forEach(member -> connected.add(member.connection().principal()));
        appliedSeq.keySet().retainAll(connected);
    }

    int appliedSeqSize() {
        return appliedSeq.size();
    }

    private void applyRoles(long seq, Map<Principal, Optional<PageRole>> roles) {
        roles.keySet().removeIf(principal -> appliedSeq.getOrDefault(principal, 0L) > seq);
        roles.keySet().forEach(principal -> appliedSeq.put(principal, seq));
        for (Member member : List.copyOf(members.values())) {
            Optional<PageRole> next = roles.get(member.connection().principal());
            if (next == null) {
                continue;
            }
            if (next.isEmpty()) {
                // 닫기는 비동기라 소켓이 정말 닫히기 전에 받은 묶음이 더 들어올 수 있다. 멤버에서 먼저 빼야 그 묶음을
                // 확정하지 않는다(process 는 멤버가 아닌 연결의 묶음을 버린다). 나중에 오는 leave 는 할 일이 없다.
                removeMember(member);
                member.connection().close(CloseCodes.FORBIDDEN, "이 페이지에 대한 권한이 회수되었다");
                continue;
            }
            PageRole role = member.capped ? next.get().cap(PageRole.VIEW) : next.get();
            if (role != member.role) {
                member.role = role;
                member.connection().send(new ServerMessage.Role(role));
            }
        }
        finishReauthorize();
    }

    /** 연결이 끊겼다. 다른 연결에 퇴장을 알린다. 마지막 연결이었으면 일정 시간 뒤 세션을 내린다. */
    public void leave(Connection connection) {
        executor.execute(() -> {
            Member member = members.get(connection.id());
            if (member != null) {
                removeMember(member);
            }
        });
    }

    private void removeMember(Member member) {
        members.remove(member.connection().id());
        pruneAppliedSeq();
        tellOthers(member, new ServerMessage.Presence(member.peer(), true));
        scheduleUnloadIfEmpty();
    }

    private void tellOthers(Member from, ServerMessage message) {
        for (Member member : members.values()) {
            if (member != from) {
                member.connection().send(message);
            }
        }
    }

    private void scheduleUnloadIfEmpty() {
        if (members.isEmpty() && !closed) {
            idleSince = clock.instant();
            scheduler.schedule(() -> executor.execute(this::unloadIfIdle), settings.unloadDelay());
        }
    }

    private void ensureLoaded() {
        if (server == null) {
            long started = System.nanoTime();
            DocumentStore.Snapshot snapshot = store.load(docId);
            metrics.sessionLoad(System.nanoTime() - started);
            server = new ServerDocument(snapshot.document(), snapshot.rev(), settings.historyWindow());
            lastSnapshotRev = snapshot.snapshotRev();
            // 제목을 동기화하기 전에 만든 페이지도 처음 열릴 때 채워진다. 받는 쪽은 리비전이 같으면 바꾸지 않는다.
            publishTitle(true);
            // 검색을 만들기 전의 문서도 처음 열릴 때 색인된다.
            bodyChanged();
        }
    }

    private void doJoin(Connection connection, Hello hello, PageRole role, String name) {
        long rev = server.rev();
        if (hello.lastRev() < 0 || (hello.resume() && hello.lastRev() > rev)) {
            connection.close(CloseCodes.PROTOCOL, "lastRev " + hello.lastRev() + " 은 이 문서에 없는 리비전이다");
            return;
        }
        boolean capped = members.size() >= settings.maxConnections();
        if (capped) {
            role = role.cap(PageRole.VIEW);
        }
        List<Peer> peers = members.values().stream().map(Member::peer).toList();
        Member member = new Member(connection, hello.clientId(), role, capped, name);
        members.put(connection.id(), member);
        idleSince = null;
        connection.send(welcome(hello, role, peers));
        // 입장을 알린다. 아직 커서는 없다.
        tellOthers(member, new ServerMessage.Presence(member.peer(), false));
    }

    /**
     * 처음 여는 연결에는 전체 문서를 보낸다. 이어 붙는 연결(resume)에는 놓친 묶음만 보낸다. 다만 리비전 차이가 너무 크거나,
     * 놓친 첫 묶음이 너무 오래전에 확정되었으면(그 뒤로 한 번도 확인하지 못했다는 뜻이다) 전체 문서와 rebase=false 를 보낸다
     * (7.5).
     */
    private ServerMessage.Welcome welcome(Hello hello, PageRole role, List<Peer> peers) {
        long rev = server.rev();
        long lastRev = hello.lastRev();
        if (!hello.resume()) {
            return new ServerMessage.Welcome(rev, role, true, server.document(), null, peers);
        }
        if (rev - lastRev > settings.rebaseMaxGap()) {
            return new ServerMessage.Welcome(rev, role, false, server.document(), null, peers);
        }
        List<StoredChange> missed = store.changesSince(docId, lastRev);
        if (!missed.isEmpty() && missed.get(0).committedAt().plus(settings.rebaseMaxAge()).isBefore(clock.instant())) {
            return new ServerMessage.Welcome(rev, role, false, server.document(), null, peers);
        }
        return new ServerMessage.Welcome(rev, role, true, null, missed, peers);
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
        try {
            for (Pending item : batch) {
                process(item, now, commits, replies);
            }
        } catch (RuntimeException e) {
            // 묶음을 처리하다 저장소를 읽지 못했다(중복 확인 등). 메모리의 문서는 이미 이 차례의 앞 묶음들을 적용했을 수
            // 있어 저장소와 어긋난다. 커밋 실패와 같이 세션을 버린다. 처리하지 못한 묶음은 클라이언트가 재접속해 같은 순번으로
            // 다시 보낸다. 그냥 두면 예외가 실행기로 새어 묶음이 말없이 사라지고 클라이언트는 답을 영영 받지 못한다.
            LOG.log(System.Logger.Level.ERROR, "문서 " + docId + " 의 묶음을 처리하다 저장소를 읽지 못했다. 세션을 버린다", e);
            discard();
            return;
        }
        if (!commits.isEmpty()) {
            metrics.commitBatch(commits.size());
            try {
                store.append(docId, commits);
            } catch (RuntimeException e) {
                // 메모리의 문서가 저장소보다 앞서 버렸다. 세션을 버리고, 클라이언트는 재접속해서 저장소 기준으로 다시 맞춘다
                // (docs/03-ARCHITECTURE.md 5.3).
                LOG.log(System.Logger.Level.ERROR, "문서 " + docId + " 커밋 실패. 세션을 버린다", e);
                discard();
                return;
            }
            if (server.rev() - lastSnapshotRev >= settings.snapshotInterval()) {
                saveSnapshot();
            }
            publishTitle(false);
            publishMentions(commits);
            bodyChanged();
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
        if (member.seqFloor >= 0 && seq <= member.seqFloor) {
            // 정상 클라이언트는 한 연결에서 순번을 되돌리지 않는다. 받아들이면 저장소의 유일 제약에 걸려 커밋이 실패하고 문서의
            // 모든 연결이 끊긴다.
            violate(member, replies, "순번 " + seq + " 이 이 연결의 앞 순번 " + member.seqFloor + " 보다 크지 않다");
            return;
        }
        Optional<ChangePolicy.Refusal> refused = change.ops().isEmpty()
                ? Optional.of(new ChangePolicy.Refusal("empty", "빈 묶음"))
                : policy.refuse(change.ops(), member.role());
        if (refused.isPresent()) {
            if (member.seqFloor >= 0) {
                member.seqFloor = seq;
            }
            long rev = server.rev();
            metrics.nack(refused.get().category());
            replies.add(() -> sender.send(
                    new ServerMessage.Nack(seq, refused.get().message(), rev, refused.get().category())));
            return;
        }
        // 세션이 다시 올라오면 ServerDocument 는 예전 순번을 모른다. 연결의 첫 묶음에서 저장소를 한 번 확인한다 (7.5 의 4번).
        // 재접속한 클라이언트가 다시 보내는 것(이미 확정되었을 수 있는 것)은 언제나 그 연결의 첫 묶음이다.
        if (member.seqFloor < 0) {
            DocumentStore.ClientSeqs seqs = store.clientSeqs(docId, member.clientId(), seq);
            if (seqs.committedRev().isPresent()) {
                long rev = seqs.committedRev().getAsLong();
                member.seqFloor = Math.max(seq, seqs.highestSeq());
                replies.add(() -> ack(sender, seq, rev, item.receivedNanos()));
                return;
            }
            if (seq <= seqs.highestSeq()) {
                violate(member, replies, "순번 " + seq + " 은 확정되지 않았는데 더 큰 순번 " + seqs.highestSeq() + " 이 이미 있다");
                return;
            }
        }
        member.seqFloor = seq;
        metrics.transforms(Math.max(0, server.rev() - change.baseRev()));
        Submission submission = new Submission(member.clientId(), seq, change.baseRev(), change.ops());
        switch (server.submit(submission, policy::checkSize)) {
            case SubmitResult.Accepted accepted -> {
                StoredChange stored = new StoredChange(
                        accepted.change().rev(), sender.userId(), member.clientId(), seq, accepted.change().ops(), now);
                commits.add(stored);
                replies.add(() -> {
                    broadcast(sender, stored);
                    metrics.ackLatency(System.nanoTime() - item.receivedNanos());
                });
            }
            case SubmitResult.Duplicate duplicate ->
                    replies.add(() -> ack(sender, seq, duplicate.rev(), item.receivedNanos()));
            case SubmitResult.Rejected rejected -> {
                long rev = server.rev();
                // 변환할 수 없는 충돌, 적용할 수 없는 연산, 문서 크기 한도
                metrics.nack("rejected");
                replies.add(() -> sender.send(new ServerMessage.Nack(seq, rejected.reason(), rev, "rejected")));
            }
            case SubmitResult.Stale stale -> {
                // nack 하면 클라이언트는 묶음을 버린다. 연결을 닫아 resume 으로 다시 붙게 하면 대기 편집을 살려 다시 맞춘다.
                String reason = "기준 리비전 " + change.baseRev() + " 이 기록 창(" + stale.oldestBaseRev()
                        + " 부터)보다 오래되었다. 다시 접속해 맞춘다";
                replies.add(() -> sender.close(CloseCodes.SLOW, reason));
            }
        }
    }

    /**
     * 규약을 어긴 연결을 닫는다. 같은 차례에 이 연결이 보낸 뒤 묶음은 순번 검사에서 걸러진다. 멤버는 닫힘이 알려질 때 평소처럼
     * 나간다(남에게 퇴장을 알린다).
     */
    private void violate(Member member, List<Runnable> replies, String reason) {
        member.seqFloor = Long.MAX_VALUE;
        LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 연결 " + member.connection.id() + " 을 규약 위반으로 닫는다: "
                + reason);
        replies.add(() -> member.connection.close(CloseCodes.PROTOCOL, reason));
    }

    private void ack(Connection sender, long seq, long rev, long receivedNanos) {
        sender.send(new ServerMessage.Ack(seq, rev));
        metrics.ackLatency(System.nanoTime() - receivedNanos);
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
        if (server != null && server.rev() > lastSnapshotRev) {
            saveSnapshot();
        }
        publishBody();
        unloader.unloaded(this);
    }

    /**
     * 커밋한 묶음에서 새로 넣은 멘션을 background 에서 알린다 (C-4, D-46). mention 서식의 값은 "멘션ID:사용자ID" 다. 실패해도
     * 편집에는 영향이 없다.
     */
    private void publishMentions(List<StoredChange> commits) {
        for (StoredChange change : commits) {
            for (Op op : change.ops()) {
                if (!(op instanceof Op.TextOp text)) {
                    continue;
                }
                for (Component component : text.delta().components()) {
                    if (component instanceof Component.Insert insert
                            && insert.attrs().get(MENTION_MARK) instanceof String value) {
                        mention(change.userId(), text.blockId(), value);
                    }
                }
            }
        }
    }

    private void mention(UUID actorId, String blockId, String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return;
        }
        UUID mentioned;
        try {
            mentioned = UUID.fromString(value.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return;
        }
        String mentionId = value.substring(0, colon);
        Block block = server.document().get(blockId);
        String preview = block == null ? "" : block.text().plainText();
        try {
            background.execute(() -> {
                try {
                    mentions.mentioned(docId, actorId, mentionId, mentioned, preview);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 멘션을 알리지 못했다", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "실행기가 닫혀 멘션을 알리지 못했다", e);
        }
    }

    /**
     * 루트 블록의 글이 바뀌었으면 background 에서 알린다 (D-21). 커밋한 뒤에만 부른다. 실패해도 편집에는 영향이 없다. 다음에
     * 제목이 바뀌거나 세션이 다시 올라올 때 다시 쓴다.
     *
     * @param always 바뀌지 않았어도 알린다. 세션을 올릴 때 쓴다
     */
    private void publishTitle(boolean always) {
        String title = server.document().get(server.document().rootId()).text().plainText();
        if (!always && title.equals(lastTitle)) {
            return;
        }
        lastTitle = title;
        long rev = server.rev();
        try {
            background.execute(() -> {
                try {
                    titles.update(docId, title, rev);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 제목을 쓰지 못했다", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "실행기가 닫혀 문서 " + docId + " 의 제목을 쓰지 못했다", e);
        }
    }

    /** 지금 문서를 별도 스레드에서 스냅샷으로 남긴다. */
    private void saveSnapshot() {
        Document document = server.document();
        long rev = server.rev();
        lastSnapshotRev = rev;
        try {
            background.execute(() -> {
                try {
                    store.saveSnapshot(docId, document, rev);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 리비전 " + rev + " 스냅샷 저장 실패", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "실행기가 닫혀 문서 " + docId + " 의 스냅샷을 남기지 못했다", e);
        }
    }

    /** 커밋이 있었다. 간격 안에 이미 예약했으면 그때 함께 알린다. */
    private void bodyChanged() {
        bodyDirty = true;
        if (!bodyScheduled) {
            bodyScheduled = true;
            scheduler.schedule(() -> executor.execute(this::publishBody), BODY_INTERVAL);
        }
    }

    /**
     * 검색에 쓸 본문을 background 에서 알린다 (F-1, D-47). 세션을 버린 뒤(discard)에는 부르지 않는다. 메모리의 문서가 저장소보다
     * 앞설 수 있어서다. 실패해도 편집에는 영향이 없다. 다음 커밋이나 다음에 세션을 올릴 때 다시 쓴다.
     */
    private void publishBody() {
        bodyScheduled = false;
        if (!bodyDirty || server == null || discarded) {
            return;
        }
        bodyDirty = false;
        String body = plainBody(server.document());
        long rev = server.rev();
        try {
            background.execute(() -> {
                try {
                    bodies.update(docId, body, rev);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 검색 본문을 쓰지 못했다", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "실행기가 닫혀 문서 " + docId + " 의 검색 본문을 쓰지 못했다", e);
        }
    }

    /** 제목(루트)을 뺀, 지우지 않은 블록의 글을 화면 순서로 잇는다. 부모별 자식 목록을 한 번 만들어 쓴다. */
    static String plainBody(Document document) {
        Map<String, List<Block>> children = new HashMap<>();
        for (Block block : document.blocks()) {
            if (block.parentId() != null && !block.deleted()) {
                children.computeIfAbsent(block.parentId(), id -> new ArrayList<>()).add(block);
            }
        }
        Comparator<Block> order = Comparator.comparing(Block::pos).thenComparing(Block::id);
        children.values().forEach(list -> list.sort(order));
        StringBuilder text = new StringBuilder();
        Deque<Block> stack = new ArrayDeque<>();
        List<Block> top = children.getOrDefault(document.rootId(), List.of());
        for (int i = top.size() - 1; i >= 0; i--) {
            stack.push(top.get(i));
        }
        while (!stack.isEmpty()) {
            Block block = stack.pop();
            String line = block.text().plainText();
            if (!line.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(line);
            }
            List<Block> below = children.getOrDefault(block.id(), List.of());
            for (int i = below.size() - 1; i >= 0; i--) {
                stack.push(below.get(i));
            }
        }
        return text.toString();
    }

    /** 이 문서의 모든 연결에 알린다. 편집과 상관없는 알림(댓글이 바뀜 등)이다. */
    public void announce(ServerMessage message) {
        executor.execute(() -> {
            if (!closed) {
                members.values().forEach(member -> member.connection().send(message));
            }
        });
    }

    /**
     * 다른 노드에 세션을 넘긴다 (docs/03-ARCHITECTURE.md 5.4). 모인 묶음을 먼저 확정하고, 스냅샷을 남기고, 모든 연결을 4009 로
     * 닫는다. 클라이언트는 재접속해 새 노드에서 이어 간다. 세션을 내리면 소유 리스가 풀린다.
     */
    public void handOff() {
        executor.execute(() -> {
            if (closed) {
                return;
            }
            if (!pending.isEmpty()) {
                drain();
            }
            if (closed) {
                return;
            }
            closed = true;
            // 스냅샷은 지름길일 뿐이다. 남기지 못해도 새 노드는 로그로 복구한다.
            if (server != null && server.rev() > lastSnapshotRev) {
                saveSnapshot();
            }
            publishBody();
            for (Member member : members.values()) {
                member.connection().close(CloseCodes.MOVED, "문서가 다른 노드로 옮겨 간다. 다시 접속한다");
            }
            members.clear();
            unloader.unloaded(this);
        });
    }

    /** 소유 리스를 잃었다. 다른 노드가 이 문서를 맡았을 수 있어 커밋하지 않고 버린다(연결은 4009). */
    public void leaseLost() {
        executor.execute(() -> {
            if (!closed) {
                LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 소유 리스를 잃어 세션을 버린다");
                discard();
            }
        });
    }

    private void discard() {
        closed = true;
        discarded = true;
        pending.clear();
        for (Member member : members.values()) {
            member.connection().close(CloseCodes.MOVED, "문서 세션을 다시 연다");
        }
        members.clear();
        unloader.unloaded(this);
    }
}
