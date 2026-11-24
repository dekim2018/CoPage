package com.copage.notification.out;

import com.copage.notification.app.NotificationRepository;
import com.copage.notification.domain.Notification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** notifications (D-46). */
public final class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcClient jdbc;

    public JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Notification> insert(Notification n, String dedupeKey) {
        int inserted = jdbc.sql("""
                        INSERT INTO notifications (id, user_id, kind, doc_id, actor_id, preview, dedupe_key, created_at)
                        VALUES (:id, :userId, :kind, :docId, :actorId, :preview, :dedupeKey, :createdAt)
                        ON CONFLICT (dedupe_key) DO NOTHING
                        """)
                .param("id", n.id())
                .param("userId", n.userId())
                .param("kind", n.kind().name())
                .param("docId", n.docId())
                .param("actorId", n.actorId())
                .param("preview", n.preview())
                .param("dedupeKey", dedupeKey)
                .param("createdAt", Timestamp.from(n.createdAt()))
                .update();
        return inserted == 1 ? Optional.of(n) : Optional.empty();
    }

    @Override
    public List<Notification> recent(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT n.id, n.user_id, n.kind, n.doc_id, n.actor_id, u.name AS actor_name, n.preview, n.created_at,
                               n.read_at
                        FROM notifications n LEFT JOIN users u ON u.id = n.actor_id
                        WHERE n.user_id = :userId
                        ORDER BY n.created_at DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, row) -> notification(rs))
                .list();
    }

    @Override
    public long unread(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    @Override
    public void markRead(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) {
            jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL")
                    .param("userId", userId)
                    .update();
            return;
        }
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :userId AND id = ANY(:ids) AND read_at IS NULL")
                .param("userId", userId)
                .param("ids", ids.toArray(UUID[]::new))
                .update();
    }

    private static Notification notification(ResultSet rs) throws SQLException {
        Timestamp read = rs.getTimestamp("read_at");
        return new Notification(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Notification.Kind.valueOf(rs.getString("kind")),
                rs.getObject("doc_id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"),
                rs.getString("preview"),
                rs.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant());
    }
}
