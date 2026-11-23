package com.copage.identity.app;

import com.copage.identity.domain.User;
import java.util.Optional;
import java.util.UUID;

/** 사용자 저장소 포트. */
public interface UserRepository {

    /** @throws DuplicateEmailException 같은 이메일의 사용자가 이미 있을 때 */
    void create(User user, String passwordHash);

    Optional<Credentials> findByEmail(String email);

    Optional<User> findById(UUID id);

    void rename(UUID id, String name);

    record Credentials(User user, String passwordHash) {
    }

    class DuplicateEmailException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DuplicateEmailException(String email) {
            super("이미 가입한 이메일이다: " + email);
        }
    }
}
