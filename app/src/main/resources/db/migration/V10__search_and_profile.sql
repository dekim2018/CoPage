-- 검색 (F-1, F-2, D-47) 과 커서 색 (A-3). 표와 칸을 더하기만 한다.

-- pg_trgm 은 PostgreSQL 기본 이미지의 contrib 에 있고 신뢰된(trusted) 확장이라 DB 주인이 만들 수 있다. 확장은 DB 에 하나라
-- public 에 두고 연산자 클래스를 그 이름으로 부른다. 다른 스키마로 마이그레이션해도(시험) 찾을 수 있다.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

ALTER TABLE users ADD COLUMN IF NOT EXISTS cursor_color text;

-- 본문을 일반 텍스트로 펼친 것. 제목은 pages.title 을 쓴다. 문서 세션이 편집이 조용해지면 갱신한다.
CREATE TABLE IF NOT EXISTS page_search (
    page_id    uuid        PRIMARY KEY REFERENCES pages (id) ON DELETE CASCADE,
    body       text        NOT NULL,
    rev        bigint      NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS page_search_body_trgm ON page_search USING gin (body public.gin_trgm_ops);
CREATE INDEX IF NOT EXISTS pages_title_trgm ON pages USING gin (title public.gin_trgm_ops);
