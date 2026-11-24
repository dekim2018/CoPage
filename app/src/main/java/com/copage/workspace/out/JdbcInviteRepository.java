package com.copage.workspace.out;

import com.copage.workspace.app.InviteRepository;
import com.copage.workspace.domain.Invite;
import com.copage.workspace.domain.WorkspaceRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** workspace_invites 테이블 (docs/06-DATA-MODEL.md 8.5). */
public final class JdbcInviteRepository implements InviteRepository {

    private static final String COLUMNS =
            "id, workspace_id, email, role, created_by, created_at, expires_at, accepted_at";

    private final JdbcClient jdbc;

    public JdbcInviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(Invite invite, String tokenHash) {
        jdbc.sql("""
                        INSERT INTO workspace_invites
                            (id, workspace_id, email, role, token_hash, created_by, created_at, expires_at)
                        VALUES (:id, :workspaceId, :email, :role, :tokenHash, :createdBy, :createdAt, :expiresAt)
                        """)
                .param("id", invite.id())
                .param("workspaceId", invite.workspaceId())
                .param("email", invite.email())
                .param("role", invite.role().name())
                .param("tokenHash", tokenHash)
                .param("createdBy", invite.createdBy())
                .param("createdAt", Timestamp.from(invite.createdAt()))
                .param("expiresAt", Timestamp.from(invite.expiresAt()))
                .update();
    }

    @Override
    public Optional<Invite> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workspace_invites WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query((rs, row) -> invite(rs))
                .optional();
    }

    @Override
    public List<Invite> pending(UUID workspaceId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """

                        FROM workspace_invites
                        WHERE workspace_id = :workspaceId AND accepted_at IS NULL AND expires_at > :now
                        ORDER BY created_at DESC, id
                        """)
                .param("workspaceId", workspaceId)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> invite(rs))
                .list();
    }

    @Override
    public boolean revoke(UUID workspaceId, UUID inviteId) {
        return jdbc.sql("""
                        DELETE FROM workspace_invites
                        WHERE id = :id AND workspace_id = :workspaceId AND accepted_at IS NULL
                        """)
                .param("id", inviteId)
                .param("workspaceId", workspaceId)
                .update() == 1;
    }

    @Override
    public boolean markAccepted(UUID inviteId, UUID userId, Instant at) {
        return jdbc.sql("""
                        UPDATE workspace_invites SET accepted_at = :at, accepted_by = :userId
                        WHERE id = :id AND accepted_at IS NULL
                        """)
                .param("id", inviteId)
                .param("userId", userId)
                .param("at", Timestamp.from(at))
                .update() == 1;
    }

    private static Invite invite(ResultSet rs) throws SQLException {
        Timestamp accepted = rs.getTimestamp("accepted_at");
        return new Invite(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("email"),
                WorkspaceRole.valueOf(rs.getString("role")),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                accepted == null ? null : accepted.toInstant());
    }
}
