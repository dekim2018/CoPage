-- 페이지 공유 링크 (docs/06-DATA-MODEL.md 8.5, docs/07-SECURITY.md 9.4 의 4번, D-41).
-- 링크를 가진 사람은 로그인하지 않아도 그 페이지 하나를 링크의 등급으로 본다. 하위 페이지로는 내려가지 않는다.
-- 페이지마다 링크는 하나다. 토큰은 만들 때만 돌려주고 해시만 둔다. 끄면 행을 지운다.

CREATE TABLE share_links (
    id         uuid        PRIMARY KEY,
    page_id    uuid        NOT NULL UNIQUE REFERENCES pages (id) ON DELETE CASCADE,
    -- SHA-256 hex
    token_hash text        NOT NULL UNIQUE,
    level      text        NOT NULL CHECK (level IN ('VIEW', 'COMMENT', 'EDIT')),
    -- 없으면 끌 때까지 쓴다
    expires_at timestamptz,
    created_by uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);
