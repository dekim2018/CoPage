package com.copage.identity.out;

import com.copage.identity.app.AuthService;
import com.copage.identity.app.UserDirectory;
import com.copage.identity.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** users 테이블에서 다른 모듈이 쓸 사용자 정보를 읽는다. */
public final class JdbcUserDirectory implements UserDirectory {

    private final JdbcClient jdbc;

    public JdbcUserDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, name, created_at, cursor_color FROM users WHERE email = :email")
                .param("email", AuthService.normalizeEmail(email))
                .query((rs, row) -> JdbcUserRepository.user(rs))
                .optional();
    }

    @Override
    public List<User> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, email, name, created_at, cursor_color FROM users WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, row) -> JdbcUserRepository.user(rs))
                .list();
    }
}
