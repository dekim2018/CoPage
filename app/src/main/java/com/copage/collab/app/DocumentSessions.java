package com.copage.collab.app;

import com.copage.collab.domain.ClientMessage.Change;
import com.copage.collab.domain.ClientMessage.Hello;
import com.copage.collab.domain.PageRole;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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

    public DocumentSessions(
            DocumentStore store,
            ChangePolicy policy,
            DocumentSession.Settings settings,
            Executor executor,
            Executor background,
            DocumentSession.Scheduler scheduler,
            Clock clock) {
        this.store = store;
        this.policy = policy;
        this.settings = settings;
        this.executor = executor;
        this.background = background;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** hello 를 받은 연결을 문서 세션에 넣는다. welcome 은 세션이 보낸다. */
    public void join(UUID docId, Connection connection, Hello hello, PageRole role) {
        DocumentSession session = sessions.computeIfAbsent(docId, this::create);
        // 세션이 막 내려가는 중이었으면 새 세션으로 다시 시도한다.
        session.join(connection, hello, role, () -> join(docId, connection, hello, role));
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

    /** 지금 메모리에 올라와 있는 세션 수. */
    public int size() {
        return sessions.size();
    }

    private DocumentSession create(UUID docId) {
        return new DocumentSession(
                docId, store, policy, settings, executor, background, scheduler, clock, s -> sessions.remove(s.docId(), s));
    }
}
