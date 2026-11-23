package com.copage.identity.app;

import com.copage.identity.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 다른 모듈이 사용자를 찾을 때 쓰는 공개 인터페이스 (docs/03-ARCHITECTURE.md 5.1). 비밀번호 해시는 내놓지 않는다. */
public interface UserDirectory {

    /** 이메일은 {@link AuthService#normalizeEmail} 로 맞춰서 찾는다. */
    Optional<User> findByEmail(String email);

    /** 없는 ID 는 빼고 돌려준다. 순서는 정하지 않는다. */
    List<User> findAllById(Collection<UUID> ids);
}
