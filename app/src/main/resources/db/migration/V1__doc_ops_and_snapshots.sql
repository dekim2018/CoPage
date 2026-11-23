-- 문서의 연산 로그와 스냅샷 (docs/06-DATA-MODEL.md 8.1).
-- 문서의 원본은 연산 로그다. 스냅샷은 로그를 처음부터 다시 적용하지 않기 위한 지름길이다.

CREATE TABLE doc_ops (
    doc_id      uuid        NOT NULL,
    rev         bigint      NOT NULL,
    user_id     uuid        NOT NULL,
    client_id   text        NOT NULL,
    client_seq  bigint      NOT NULL,
    ops         jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- 한 문서의 한 리비전은 하나뿐이다. 두 노드가 동시에 쓰면 한쪽이 실패한다 (5.3).
    PRIMARY KEY (doc_id, rev),
    -- 같은 묶음은 한 번만 들어간다 (7.5).
    UNIQUE (doc_id, client_id, client_seq)
);

CREATE TABLE doc_snapshots (
    doc_id      uuid        NOT NULL,
    rev         bigint      NOT NULL,
    content     jsonb       NOT NULL,
    block_count integer     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, rev)
);
