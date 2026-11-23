-- 사용자 (docs/06-DATA-MODEL.md 8.5). 이메일은 앞뒤 공백을 빼고 소문자로 바꿔 저장한다.

CREATE TABLE users (
    id            uuid        PRIMARY KEY,
    email         text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    name          text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
