package com.copage.comment.out;

import com.copage.comment.app.CommentRepository;
import com.copage.comment.domain.Comment;
import com.copage.comment.domain.CommentThread;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** comment_threads, comments (D-46). */
public final class JdbcCommentRepository implements CommentRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcCommentRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void createThread(CommentThread thread, Comment first) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO comment_threads (id, doc_id, quote, created_by, created_at)
                            VALUES (:id, :docId, :quote, :createdBy, :createdAt)
                            """)
                    .param("id", thread.id())
                    .param("docId", thread.docId())
                    .param("quote", thread.quote())
                    .param("createdBy", thread.createdBy())
                    .param("createdAt", Timestamp.from(thread.createdAt()))
                    .update();
            addComment(first);
        });
    }

    @Override
    public void addComment(Comment comment) {
        jdbc.sql("""
                        INSERT INTO comments (id, thread_id, author_id, body, created_at)
                        VALUES (:id, :threadId, :authorId, :body, :createdAt)
                        """)
                .param("id", comment.id())
                .param("threadId", comment.threadId())
                .param("authorId", comment.authorId())
                .param("body", comment.body())
                .param("createdAt", Timestamp.from(comment.createdAt()))
                .update();
    }

    private record ThreadRow(UUID id, String quote, UUID createdBy, Instant createdAt, Instant resolvedAt,
            String resolvedByName) {
    }

    @Override
    public List<CommentThread> threads(UUID docId) {
        List<ThreadRow> rows = jdbc.sql("""
                        SELECT t.id, t.quote, t.created_by, t.created_at, t.resolved_at, u.name AS resolved_by_name
                        FROM comment_threads t LEFT JOIN users u ON u.id = t.resolved_by
                        WHERE t.doc_id = :docId
                        ORDER BY t.created_at, t.id
                        """)
                .param("docId", docId)
                .query((rs, row) -> {
                    Timestamp resolved = rs.getTimestamp("resolved_at");
                    return new ThreadRow(rs.getObject("id", UUID.class), rs.getString("quote"),
                            rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                            resolved == null ? null : resolved.toInstant(), rs.getString("resolved_by_name"));
                })
                .list();
        Map<UUID, List<Comment>> comments = new LinkedHashMap<>();
        rows.forEach(row -> comments.put(row.id(), new ArrayList<>()));
        jdbc.sql("""
                        SELECT c.id, c.thread_id, c.author_id, u.name AS author_name, c.body, c.created_at
                        FROM comments c JOIN comment_threads t ON t.id = c.thread_id JOIN users u ON u.id = c.author_id
                        WHERE t.doc_id = :docId
                        ORDER BY c.created_at, c.id
                        """)
                .param("docId", docId)
                .query((rs, row) -> new Comment(rs.getObject("id", UUID.class), rs.getObject("thread_id", UUID.class),
                        rs.getObject("author_id", UUID.class), rs.getString("author_name"), rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()))
                .list()
                .forEach(comment -> comments.get(comment.threadId()).add(comment));
        return rows.stream()
                .map(row -> new CommentThread(row.id(), docId, row.quote(), row.createdBy(), row.createdAt(),
                        row.resolvedAt(), row.resolvedByName(), List.copyOf(comments.get(row.id()))))
                .toList();
    }

    @Override
    public Optional<UUID> docOf(UUID threadId) {
        return jdbc.sql("SELECT doc_id FROM comment_threads WHERE id = :id")
                .param("id", threadId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Set<UUID> participants(UUID threadId) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT author_id FROM comments WHERE thread_id = :id")
                .param("id", threadId)
                .query(UUID.class)
                .list());
    }

    @Override
    public boolean setResolved(UUID threadId, Instant resolvedAt, UUID resolvedBy) {
        if (resolvedAt == null) {
            return jdbc.sql("UPDATE comment_threads SET resolved_at = NULL, resolved_by = NULL WHERE id = :id")
                    .param("id", threadId)
                    .update() == 1;
        }
        return jdbc.sql("UPDATE comment_threads SET resolved_at = :at, resolved_by = :by WHERE id = :id")
                .param("id", threadId)
                .param("at", Timestamp.from(resolvedAt))
                .param("by", resolvedBy)
                .update() == 1;
    }
}
