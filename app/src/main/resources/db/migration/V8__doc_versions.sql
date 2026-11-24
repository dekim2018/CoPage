-- 버전 기록 (docs/08-FEATURES.md 10.1, V-1, V-2, D-44).
-- 버전은 연산 로그의 한 리비전을 가리킨다. 그 시점의 문서는 그 리비전 이하의 스냅샷에 남은 연산을 적용해 만든다.
-- AUTO 는 서버의 주기 작업이 남긴다(편집이 2분 조용하거나 마지막 버전에서 10분이 지났을 때). NAMED 는 사람이 이름을 붙인
-- 것, RESTORE 는 복원한 직후의 시점이다.

CREATE TABLE doc_versions (
    id         uuid        PRIMARY KEY,
    doc_id     uuid        NOT NULL,
    rev        bigint      NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('AUTO', 'NAMED', 'RESTORE')),
    name       text,
    created_by uuid        REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 여러 노드가 같은 주기 작업을 돌려도 AUTO 는 한 번만 남는다.
    UNIQUE (doc_id, rev, kind)
);

-- 문서의 버전 목록(최근부터)
CREATE INDEX doc_versions_doc ON doc_versions (doc_id, rev DESC);

-- 주기 작업이 최근에 바뀐 문서를 찾는다.
CREATE INDEX doc_ops_created ON doc_ops (created_at);
