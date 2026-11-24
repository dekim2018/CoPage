-- 워크스페이스 초대 (docs/06-DATA-MODEL.md 8.5, docs/09-API.md 11.2, D-40).
-- 메일은 보내지 않는다(08-FEATURES). OWNER 가 만든 초대 링크를 직접 건넨다. 토큰은 만들 때 한 번만 돌려주고 해시만 둔다.
-- 초대는 그 이메일로 가입한 사용자만 받을 수 있다. 링크가 다른 사람에게 넘어가도 쓸 수 없다.

CREATE TABLE workspace_invites (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- AuthService.normalizeEmail 로 맞춘 값(앞뒤 공백 없이 소문자)
    email        text        NOT NULL,
    role         text        NOT NULL CHECK (role IN ('MEMBER', 'GUEST')),
    -- SHA-256 hex
    token_hash   text        NOT NULL UNIQUE,
    created_by   uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    accepted_at  timestamptz,
    accepted_by  uuid        REFERENCES users (id)
);

-- OWNER 가 보는 대기 중인 초대 목록
CREATE INDEX workspace_invites_workspace ON workspace_invites (workspace_id, created_at);
