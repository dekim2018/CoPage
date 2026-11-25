package com.copage.identity.out;

import com.copage.identity.app.UserRepository;
import com.copage.identity.domain.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** users 테이블. */
public final class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(User user, String passwordHash) {
        try {
            jdbc.sql("""
                            INSERT INTO users (id, email, password_hash, name, created_at)
                            VALUES (:id, :email, :passwordHash, :name, :createdAt)
                            """)
                    .param("id", user.id())
                    .param("email", user.email())
                    .param("passwordHash", passwordHash)
                    .param("name", user.name())
                    .param("createdAt", Timestamp.from(user.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEmailException(user.email());
        }
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, name, created_at, cursor_color, password_hash FROM users WHERE email = :email")
                .param("email", email)
                .query((rs, row) -> new Credentials(user(rs), rs.getString("password_hash")))
                .optional();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT id, email, name, created_at, cursor_color FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> user(rs))
                .optional();
    }

    @Override
    public void rename(UUID id, String name) {
        jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("id", id).param("name", name).update();
    }

    @Override
    public Optional<User> findByIdentity(String provider, String subject) {
        return jdbc.sql("""
                        SELECT u.id, u.email, u.name, u.created_at, u.cursor_color
                        FROM user_identities i JOIN users u ON u.id = i.user_id
                        WHERE i.provider = :provider AND i.subject = :subject
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query((rs, row) -> user(rs))
                .optional();
    }

    @Override
    public void createWithIdentity(User user, String provider, String subject) {
        try {
            jdbc.sql("""
                            WITH created AS (
                                INSERT INTO users (id, email, password_hash, name, created_at)
                                VALUES (:id, :email, NULL, :name, :createdAt)
                                RETURNING id
                            )
                            INSERT INTO user_identities (provider, subject, user_id, email, created_at)
                            SELECT :provider, :subject, id, :email, :createdAt FROM created
                            """)
                    .param("id", user.id())
                    .param("email", user.email())
                    .param("name", user.name())
                    .param("createdAt", Timestamp.from(user.createdAt()))
                    .param("provider", provider)
                    .param("subject", subject)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEmailException(user.email());
        }
    }

    @Override
    public void recolor(UUID id, String color) {
        jdbc.sql("UPDATE users SET cursor_color = :color WHERE id = :id").param("id", id).param("color", color).update();
    }

    static User user(ResultSet rs) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("cursor_color"));
    }
}
