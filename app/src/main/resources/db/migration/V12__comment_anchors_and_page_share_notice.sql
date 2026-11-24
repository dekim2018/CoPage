-- 위치를 잃은 댓글 표시와 위치 없는 스레드 정리 (docs/08-FEATURES.md 10.2, D-50), 페이지 공유 알림 (10.3).
-- 칸을 더하고 알림 종류의 검사를 넓힌다. 지우는 데이터는 없다.

-- 본문에서 처음 서식을 본 때. 이 칸을 만들기 전의 스레드는 달 때 서식이 붙었다고 보고 만든 때로 채운다
-- (정리 작업이 그 스레드를 위치 없는 것으로 보지 않게).
ALTER TABLE comment_threads ADD COLUMN IF NOT EXISTS anchored_at timestamptz;
UPDATE comment_threads SET anchored_at = created_at WHERE anchored_at IS NULL;
-- 한때 본문에 있었던 서식이 지금은 없다(원문이 지워졌다).
ALTER TABLE comment_threads ADD COLUMN IF NOT EXISTS orphaned boolean NOT NULL DEFAULT false;

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_kind_check
    CHECK (kind IN ('MENTION', 'COMMENT_MENTION', 'REPLY', 'PAGE_SHARED'));
