package com.copage.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자. 이메일은 앞뒤 공백을 빼고 소문자로 바꾼 값이다.
 *
 * @param color 남의 화면에 보일 커서 색(#rrggbb). 고르지 않았으면 null 이고 ID 로 정한 색을 쓴다 (A-3)
 */
public record User(UUID id, String email, String name, Instant createdAt, String color) {

    public User(UUID id, String email, String name, Instant createdAt) {
        this(id, email, name, createdAt, null);
    }
}
