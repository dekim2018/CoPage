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

    void recolor(UUID id, String color);

    /** 다른 서비스 계정(구글, 깃허브)으로 이어진 사용자 (A-4, D-54). */
    Optional<User> findByIdentity(String provider, String subject);

    /**
     * 비밀번호 없는 사용자와 그 다른 서비스 계정을 함께 만든다(한 문장이라 반만 생기지 않는다).
     *
     * @throws DuplicateEmailException 같은 이메일의 사용자가 이미 있거나, 같은 계정이 방금 만들어졌을 때
     */
    void createWithIdentity(User user, String provider, String subject);

    /** @param passwordHash 다른 서비스 계정으로만 가입했으면 null 이다 */
    record Credentials(User user, String passwordHash) {
    }

    class DuplicateEmailException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DuplicateEmailException(String email) {
            super("이미 가입한 이메일이다: " + email);
        }
    }
}
