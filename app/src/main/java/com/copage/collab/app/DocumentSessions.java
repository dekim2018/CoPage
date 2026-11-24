package com.copage.collab.app;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.Cursor;
import com.copage.collab.domain.PageRole;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/** 이 노드가 들고 있는 문서 세션들. 문서를 처음 여는 연결이 오면 세션을 만들고, 세션이 내려가면 뺀다. */
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
        this.store = store;
        this.policy = policy;
        this.settings = settings;
        this.executor = executor;
        this.background = background;
        this.scheduler = scheduler;
        this.clock = clock;
        this.titles = titles;
        this.metrics = metrics;
    }

    /** hello 를 받은 연결을 문서 세션에 넣는다. welcome 은 세션이 보낸다. */
    public void join(UUID docId, Connection connection, Hello hello, PageRole role, String name) {
        DocumentSession session = sessions.computeIfAbsent(docId, this::create);
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

    /** 지금 메모리에 올라와 있는 세션 수. */
    public int size() {
        return sessions.size();
    }

    private DocumentSession create(UUID docId) {
        return new DocumentSession(
                docId, store, policy, settings, executor, background, scheduler, clock, titles, metrics,
                s -> sessions.remove(s.docId(), s));
    }
}
