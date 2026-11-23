-- 페이지 단위 권한 부여 (docs/06-DATA-MODEL.md 8.5, docs/07-SECURITY.md 9.4).
-- 부여한 등급은 그 페이지와 하위 페이지에 이어진다. 다만 하위에 restricted 경계가 있으면 그 아래로는 내려가지 않는다.

CREATE TABLE page_grants (
    page_id    uuid        NOT NULL REFERENCES pages (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id),
    level      text        NOT NULL CHECK (level IN ('VIEW', 'COMMENT', 'EDIT', 'FULL')),
    granted_by uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, user_id)
);

-- 나에게 공유된 페이지 목록, 사용자 삭제 때의 외래 키 검사
CREATE INDEX page_grants_user ON page_grants (user_id);
