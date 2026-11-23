-- 워크스페이스, 멤버, 페이지 트리 (docs/06-DATA-MODEL.md 8.5, 8.6, docs/07-SECURITY.md 9.4).
-- 페이지 ID 가 곧 문서 ID 다. doc_ops 와 doc_snapshots 의 doc_id 는 페이지 ID 를 가리킨다.

CREATE TABLE workspaces (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    owner_id   uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id),
    role         text        NOT NULL CHECK (role IN ('OWNER', 'MEMBER', 'GUEST')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

-- 내가 속한 워크스페이스 목록
CREATE INDEX workspace_members_user ON workspace_members (user_id);

CREATE TABLE pages (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    parent_id    uuid        REFERENCES pages (id),
    -- 형제 사이 순서. 분수 인덱스 키를 문자열로 비교하므로(Java String.compareTo) 바이트 순서로 정렬한다.
    pos          text        COLLATE "C" NOT NULL,
    -- 루트 블록에서 파생한다. 아직 동기화하지 않으므로 빈 문자열로 시작한다.
    title        text        NOT NULL DEFAULT '',
    restricted   boolean     NOT NULL DEFAULT false,
    created_by   uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    archived_at  timestamptz
);

-- 한 단계 하위 목록. 루트 페이지(parent_id 가 NULL)도 같은 인덱스를 쓴다.
CREATE INDEX pages_children ON pages (workspace_id, parent_id, pos);

-- 이미 있는 문서를 페이지로 옮긴다. 데이터는 지우지 않는다.
-- 문서마다 첫 연산을 쓴 사용자를 주인으로 본다. 주인마다 "가져온 문서" 워크스페이스를 하나 만들고, 그 사용자를 OWNER 로,
-- 문서를 최상위 페이지로 둔다. 순서는 첫 연산 시각 순이다. 첫 작성자가 users 에 없는 문서(로그인 도입 전의 개발 데이터)는
-- 옮기지 않는다. 그 문서의 연산 로그와 스냅샷은 그대로 남는다.

-- n 을 6자리 62진수로 쓰고 V 를 붙인다. 고정 길이라 문자열 순서가 n 의 순서와 같고, 0 으로 끝나지 않아 분수 인덱스의 규칙
-- (FractionalIndex) 을 지킨다. 62^6 개까지 쓸 수 있다.
CREATE FUNCTION pg_temp.copage_pos(n bigint) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    digits constant text := '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
    result text := '';
    rest bigint := n;
BEGIN
    FOR i IN 1..6 LOOP
        result := substr(digits, (rest % 62)::int + 1, 1) || result;
        rest := rest / 62;
    END LOOP;
    RETURN result || 'V';
END;
$$;

CREATE TEMP TABLE imported_docs ON COMMIT DROP AS
SELECT first.doc_id,
       first.user_id,
       first.created_at,
       row_number() OVER (PARTITION BY first.user_id ORDER BY first.created_at, first.doc_id) AS n
FROM (SELECT DISTINCT ON (doc_id) doc_id, user_id, created_at
      FROM doc_ops
      ORDER BY doc_id, rev) first
JOIN users ON users.id = first.user_id;

CREATE TEMP TABLE imported_workspaces ON COMMIT DROP AS
SELECT user_id, gen_random_uuid() AS workspace_id
FROM (SELECT DISTINCT user_id FROM imported_docs) owners;

INSERT INTO workspaces (id, name, owner_id)
SELECT workspace_id, '가져온 문서', user_id FROM imported_workspaces;

INSERT INTO workspace_members (workspace_id, user_id, role)
SELECT workspace_id, user_id, 'OWNER' FROM imported_workspaces;

INSERT INTO pages (id, workspace_id, parent_id, pos, created_by, created_at)
SELECT d.doc_id, w.workspace_id, NULL, pg_temp.copage_pos(d.n), d.user_id, d.created_at
FROM imported_docs d
JOIN imported_workspaces w ON w.user_id = d.user_id;
