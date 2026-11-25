-- 구글, 깃허브 로그인 (A-4, D-54). 다른 서비스 계정만 있는 사용자는 비밀번호가 없다. 칸의 제약을 풀고 표를 더한다.
-- 지우는 데이터는 없다.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE IF NOT EXISTS user_identities (
    provider   text        NOT NULL,
    subject    text        NOT NULL,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      text        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (provider, subject)
);

CREATE INDEX IF NOT EXISTS user_identities_user ON user_identities (user_id);
