-- 연산 로그 정리 (D-52) 와 즐겨찾기, 최근 본 페이지 (W-6). 칸과 표를 더하기만 한다. 지우는 데이터는 없다
-- (로그 정리는 주기 작업이 규칙에 따라 한다. 마이그레이션은 지우지 않는다).

-- 고정 스냅샷. 최근 몇 개만 남기는 정리에서 빠진다. 로그를 지운 지점과 남겨 둘 버전의 시점에 둔다.
ALTER TABLE doc_snapshots ADD COLUMN IF NOT EXISTS pinned boolean NOT NULL DEFAULT false;

-- 문서마다 연산 로그를 어디까지 지웠는지. 그보다 앞의 시점은 고정 스냅샷이 있는 리비전만 열 수 있다.
CREATE TABLE IF NOT EXISTS doc_compactions (
    doc_id       uuid        PRIMARY KEY,
    through_rev  bigint      NOT NULL,
    compacted_at timestamptz NOT NULL
);

-- 오래된 로그를 찾는 주기 작업이 쓴다.
CREATE INDEX IF NOT EXISTS doc_ops_created_at ON doc_ops (created_at);

CREATE TABLE IF NOT EXISTS page_favorites (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    page_id    uuid        NOT NULL REFERENCES pages (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, page_id)
);

CREATE TABLE IF NOT EXISTS page_visits (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    page_id    uuid        NOT NULL REFERENCES pages (id) ON DELETE CASCADE,
    visited_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, page_id)
);

CREATE INDEX IF NOT EXISTS page_visits_recent ON page_visits (user_id, visited_at DESC);
