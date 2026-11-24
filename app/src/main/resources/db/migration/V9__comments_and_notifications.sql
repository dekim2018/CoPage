-- 댓글과 알림 (docs/08-FEATURES.md 10.2, 10.3, C-1~C-4, D-46).
-- 댓글이 달린 글자는 본문의 comment 서식(스레드 ID)이 가리킨다. 위치를 따로 저장하지 않는다. quote 는 달 때의 원문이다.

CREATE TABLE comment_threads (
    id          uuid        PRIMARY KEY,
    doc_id      uuid        NOT NULL REFERENCES pages (id) ON DELETE CASCADE,
    quote       text        NOT NULL,
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid        REFERENCES users (id)
);

CREATE INDEX comment_threads_doc ON comment_threads (doc_id, created_at);

CREATE TABLE comments (
    id         uuid        PRIMARY KEY,
    thread_id  uuid        NOT NULL REFERENCES comment_threads (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX comments_thread ON comments (thread_id, created_at);

-- 알림. dedupe_key 가 같은 알림은 한 번만 남는다(되돌리기나 복원으로 같은 멘션이 다시 들어와도).
CREATE TABLE notifications (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL CHECK (kind IN ('MENTION', 'COMMENT_MENTION', 'REPLY')),
    doc_id     uuid        REFERENCES pages (id) ON DELETE CASCADE,
    actor_id   uuid        REFERENCES users (id),
    preview    text        NOT NULL DEFAULT '',
    dedupe_key text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz
);

CREATE INDEX notifications_user ON notifications (user_id, created_at DESC);
