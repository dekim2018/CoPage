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
import java.util.Objects;
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
            String resolvedByName, boolean orphaned) {
    }

    @Override
    public List<CommentThread> threads(UUID docId) {
        List<ThreadRow> rows = jdbc.sql("""
                        SELECT t.id, t.quote, t.created_by, t.created_at, t.resolved_at, t.orphaned,
                               u.name AS resolved_by_name
                        FROM comment_threads t LEFT JOIN users u ON u.id = t.resolved_by
                        WHERE t.doc_id = :docId
                        ORDER BY t.created_at, t.id
                        """)
                .param("docId", docId)
                .query((rs, row) -> {
                    Timestamp resolved = rs.getTimestamp("resolved_at");
                    return new ThreadRow(rs.getObject("id", UUID.class), rs.getString("quote"),
                            rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                            resolved == null ? null : resolved.toInstant(), rs.getString("resolved_by_name"),
                            rs.getBoolean("orphaned"));
                })
                .list();
        Map<UUID, List<Comment>> comments = new LinkedHashMap<>();
        rows.forEach(row -> comments.put(row.id(), new ArrayList<>()));
        jdbc.sql("""
                        SELECT c.id, c.thread_id, c.author_id, u.name AS author_name, c.body, c.created_at,
                               c.edited_at, c.deleted_at
                        FROM comments c JOIN comment_threads t ON t.id = c.thread_id JOIN users u ON u.id = c.author_id
                        WHERE t.doc_id = :docId
                        ORDER BY c.created_at, c.id
                        """)
                .param("docId", docId)
                .query((rs, row) -> {
                    Timestamp edited = rs.getTimestamp("edited_at");
                    return new Comment(rs.getObject("id", UUID.class), rs.getObject("thread_id", UUID.class),
                            rs.getObject("author_id", UUID.class), rs.getString("author_name"), rs.getString("body"),
                            rs.getTimestamp("created_at").toInstant(), edited == null ? null : edited.toInstant(),
                            rs.getTimestamp("deleted_at") != null);
                })
                .list()
                .forEach(comment -> comments.get(comment.threadId()).add(comment));
        return rows.stream()
                .map(row -> new CommentThread(row.id(), docId, row.quote(), row.createdBy(), row.createdAt(),
                        row.resolvedAt(), row.resolvedByName(), List.copyOf(comments.get(row.id())), row.orphaned()))
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
    public Optional<CommentRef> findComment(UUID commentId) {
        return jdbc.sql("""
                        SELECT c.id, c.thread_id, t.doc_id, c.author_id, c.deleted_at
                        FROM comments c JOIN comment_threads t ON t.id = c.thread_id
                        WHERE c.id = :id
                        """)
                .param("id", commentId)
                .query((rs, row) -> new CommentRef(rs.getObject("id", UUID.class), rs.getObject("thread_id", UUID.class),
                        rs.getObject("doc_id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getTimestamp("deleted_at") != null))
                .optional();
    }

    @Override
    public boolean editComment(UUID commentId, String body, Instant editedAt) {
        return jdbc.sql("UPDATE comments SET body = :body, edited_at = :at WHERE id = :id AND deleted_at IS NULL")
                .param("id", commentId)
                .param("body", body)
                .param("at", Timestamp.from(editedAt))
                .update() == 1;
    }

    @Override
    public boolean deleteComment(UUID commentId, Instant deletedAt) {
        Boolean threadGone = tx.execute(status -> {
            UUID threadId = jdbc.sql("""
                            UPDATE comments SET body = '', deleted_at = :at
                            WHERE id = :id AND deleted_at IS NULL
                            RETURNING thread_id
                            """)
                    .param("id", commentId)
                    .param("at", Timestamp.from(deletedAt))
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
            if (threadId == null) {
                return false;
            }
            // 남은 댓글이 없는 스레드는 지운다(댓글은 외래 키로 함께 지워진다).
            return jdbc.sql("""
                            DELETE FROM comment_threads t WHERE t.id = :threadId
                            AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.thread_id = t.id AND c.deleted_at IS NULL)
                            """)
                    .param("threadId", threadId)
                    .update() == 1;
        });
        return Boolean.TRUE.equals(threadGone);
    }

    @Override
    public List<UUID> updateAnchors(UUID docId, Set<UUID> present, Instant now) {
        String[] ids = present.stream().map(UUID::toString).toArray(String[]::new);
        return jdbc.sql("""
                        WITH next AS (
                            SELECT t.id,
                                   t.orphaned AS was_orphaned,
                                   t.id = ANY(CAST(:ids AS uuid[])) AS present,
                                   t.anchored_at
                            FROM comment_threads t WHERE t.doc_id = :docId
                        )
                        UPDATE comment_threads t
                        SET anchored_at = CASE WHEN n.present THEN coalesce(n.anchored_at, :now) ELSE n.anchored_at END,
                            orphaned = NOT n.present AND n.anchored_at IS NOT NULL
                        FROM next n
                        WHERE t.id = n.id
                          AND (n.was_orphaned IS DISTINCT FROM (NOT n.present AND n.anchored_at IS NOT NULL)
                               OR (n.present AND n.anchored_at IS NULL))
                        RETURNING t.id, n.was_orphaned IS DISTINCT FROM t.orphaned AS flipped
                        """)
                .param("docId", docId)
                .param("ids", ids)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> rs.getBoolean("flipped") ? rs.getObject("id", UUID.class) : null)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<UUID> markAnchored(UUID docId, Set<UUID> threads, Instant now) {
        String[] ids = threads.stream().map(UUID::toString).toArray(String[]::new);
        return jdbc.sql("""
                        WITH before AS (
                            SELECT id, orphaned FROM comment_threads
                            WHERE doc_id = :docId AND id = ANY(CAST(:ids AS uuid[]))
                        )
                        UPDATE comment_threads t SET anchored_at = coalesce(t.anchored_at, :now), orphaned = false
                        FROM before b
                        WHERE t.id = b.id
                        RETURNING t.id, b.orphaned AS was_orphaned
                        """)
                .param("docId", docId)
                .param("ids", ids)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> rs.getBoolean("was_orphaned") ? rs.getObject("id", UUID.class) : null)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public int purgeUnanchored(Instant before) {
        return jdbc.sql("DELETE FROM comment_threads WHERE anchored_at IS NULL AND created_at < :before")
                .param("before", Timestamp.from(before))
                .update();
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
