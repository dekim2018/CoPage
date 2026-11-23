package com.copage.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** 사용자. 이메일은 앞뒤 공백을 빼고 소문자로 바꾼 값이다. */
public record User(UUID id, String email, String name, Instant createdAt) {
}
