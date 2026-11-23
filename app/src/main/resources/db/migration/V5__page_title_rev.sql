-- 페이지 제목을 루트 블록에서 채운다 (docs/06-DATA-MODEL.md 8.5, docs/review/DECISIONS.md D-21).
-- 제목은 문서 세션이 커밋한 뒤 따로 쓴다. 쓰기가 늦게 도착해 새 제목을 낡은 제목으로 덮지 않도록, 제목을 읽은 문서 리비전을
-- 함께 적고 더 큰 리비전으로만 바꾼다.

ALTER TABLE pages ADD COLUMN title_rev bigint NOT NULL DEFAULT 0;
