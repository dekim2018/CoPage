package com.copage.collab.app;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.Cursor;
import com.copage.collab.domain.PageRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * 이 노드가 들고 있는 문서 세션들. 문서를 처음 여는 연결이 오면 소유 리스를 잡고 세션을 만들고, 세션이 내려가면 빼고 리스를 푼다
 * (docs/03-ARCHITECTURE.md 5.3, 5.4, D-43). 리스를 잡는 일과 세션을 만드는 일, 세션을 빼는 일과 리스를 푸는 일은 문서마다 한
 * 번에 하나씩 한다(맵의 compute). 내리는 세션이 리스를 푸는 순간 같은 문서의 새 세션이 리스를 잃지 않게 하기 위해서다.
 */
public final class DocumentSessions {

    private final Map<UUID, DocumentSession> sessions = new ConcurrentHashMap<>();
    private final DocumentStore store;
    private final ChangePolicy policy;
    private final DocumentSession.Settings settings;
    private final Executor executor;
    private final Executor background;
    private final DocumentSession.Scheduler scheduler;
    private final Clock clock;
    private final TitleSink titles;
    private final CollabMetrics metrics;
    private final OwnershipLease lease;
    /** 리스를 잡은 시각. 막 잡은 문서는 넘겨 달라는 요청을 받아도 넘기지 않는다(노드 사이를 오가는 것을 막는다). */
    private final Map<UUID, Instant> acquiredAt = new ConcurrentHashMap<>();
    private final Duration handoffGrace;

    /** 노드 한 대 구성(시험). 리스는 언제나 이 노드가 쥔다. */
    public DocumentSessions(
            DocumentStore store,
            ChangePolicy policy,
            DocumentSession.Settings settings,
            Executor executor,
            Executor background,
            DocumentSession.Scheduler scheduler,
            Clock clock,
            TitleSink titles,
            CollabMetrics metrics) {
        this(store, policy, settings, executor, background, scheduler, clock, titles, metrics, SOLE_OWNER, Duration.ZERO);
    }

    public DocumentSessions(
            DocumentStore store,
            ChangePolicy policy,
            DocumentSession.Settings settings,
            Executor executor,
            Executor background,
            DocumentSession.Scheduler scheduler,
            Clock clock,
            TitleSink titles,
            CollabMetrics metrics,
            OwnershipLease lease,
            Duration handoffGrace) {
        this.store = store;
        this.policy = policy;
        this.settings = settings;
        this.executor = executor;
        this.background = background;
        this.scheduler = scheduler;
        this.clock = clock;
        this.titles = titles;
        this.metrics = metrics;
        this.lease = lease;
        this.handoffGrace = handoffGrace;
    }

    /** 노드 한 대일 때의 리스. 언제나 이 노드가 쥔다. */
    private static final OwnershipLease SOLE_OWNER = new OwnershipLease() {
        @Override
        public boolean tryAcquire(UUID docId) {
            return true;
        }

        @Override
        public Renewal renew(UUID docId) {
            return Renewal.RENEWED;
        }

        @Override
        public void release(UUID docId) {
        }

        @Override
        public void requestHandoff(UUID docId) {
        }
    };

    /** hello 를 받은 연결을 문서 세션에 넣는다. welcome 은 세션이 보낸다. */
    public void join(UUID docId, Connection connection, Hello hello, PageRole role, String name) {
        DocumentSession session = sessions.compute(docId, (id, existing) -> {
            if (existing != null) {
                return existing;
            }
            if (!lease.tryAcquire(id)) {
                return null;
            }
            acquiredAt.put(id, clock.instant());
            return create(id);
        });
        if (session == null) {
            // 다른 노드가 이 문서를 맡고 있다. 넘겨 달라고 알리고, 클라이언트는 잠시 뒤 다시 붙는다(프록시가 이 노드로 보낸다면
            // 그 사이 넘어온다. 주인 노드가 죽었으면 리스가 만료된 뒤 잡는다).
            lease.requestHandoff(docId);
            connection.close(CloseCodes.MOVED, "다른 노드가 이 문서를 맡고 있다. 잠시 뒤 다시 접속한다");
            return;
        }
        // 세션이 막 내려가는 중이었으면 새 세션으로 다시 시도한다.
        session.join(connection, hello, role, name, () -> join(docId, connection, hello, role, name));
    }

    public void presence(UUID docId, Connection connection, Cursor cursor) {
        DocumentSession session = sessions.get(docId);
        if (session != null) {
            session.presence(connection, cursor);
        }
    }

    public void submit(UUID docId, Connection connection, Change change) {
        DocumentSession session = sessions.get(docId);
        if (session == null) {
            connection.close(CloseCodes.MOVED, "문서 세션이 다시 열린다");
            return;
        }
        session.submit(connection, change);
    }

    public void leave(UUID docId, Connection connection) {
        DocumentSession session = sessions.get(docId);
        if (session != null) {
            session.leave(connection);
        }
    }

    /**
     * 권한이 바뀌었을 수 있는 문서와 사용자의 열린 연결에 새 등급을 적용한다. 메모리에 없는 문서는 다음에 열 때 새 티켓이 바른
     * 등급을 준다.
     */
    public void reauthorize(Predicate<UUID> docs, Predicate<UUID> users, DocumentSession.RoleResolver resolver) {
        sessions.forEach((docId, session) -> {
            if (docs.test(docId)) {
                session.reauthorize(users, resolver);
            }
        });
    }

    /**
     * 쥔 리스를 이어 쥔다. 주기적으로 부른다(리스 시간의 1/3 쯤). 잃었으면 세션을 버린다. 저장소에 닿지 못하면 그대로 둔다. Redis
     * 장애 때 열린 편집이 끊기지 않게 하기 위해서다(ADR 0014). 그동안 겹쳐도 연산 로그의 기본 키가 막는다(ADR 0013).
     */
    public void renewLeases() {
        sessions.forEach((docId, session) -> {
            if (lease.renew(docId) == OwnershipLease.Renewal.LOST) {
                session.leaseLost();
            }
        });
    }

    /** 다른 노드가 이 문서를 넘겨 달라고 했다. 막 잡은 문서가 아니면 넘긴다. */
    public void handOffRequested(UUID docId) {
        DocumentSession session = sessions.get(docId);
        Instant since = acquiredAt.get(docId);
        if (session != null && (since == null || !since.plus(handoffGrace).isAfter(clock.instant()))) {
            session.handOff();
        }
    }

    /** 노드를 내릴 때 모든 세션을 넘긴다. 연결은 4009 로 닫히고 클라이언트는 다른 노드로 다시 붙는다. */
    public void handOffAll() {
        sessions.values().forEach(DocumentSession::handOff);
    }

    /** 시험이 세션 안을 들여다볼 때 쓴다. */
    DocumentSession session(UUID docId) {
        return sessions.get(docId);
    }

    /** 지금 메모리에 올라와 있는 세션 수. */
    public int size() {
        return sessions.size();
    }

    private DocumentSession create(UUID docId) {
        return new DocumentSession(
                docId, store, policy, settings, executor, background, scheduler, clock, titles, metrics,
                this::unloaded);
    }

    private void unloaded(DocumentSession session) {
        sessions.computeIfPresent(session.docId(), (id, current) -> {
            if (current != session) {
                return current;
            }
            acquiredAt.remove(id);
            lease.release(id);
            return null;
        });
    }
}
