-- 댓글 수정과 삭제 (D-49). 칸을 더하기만 한다. 지운 댓글은 글을 비우고 지운 시각을 남긴다(스레드의 순서가 남게).
ALTER TABLE comments ADD COLUMN IF NOT EXISTS edited_at timestamptz;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
