# 8. 저장 구조

문서의 원본은 연산 로그다. 스냅샷은 로그를 처음부터 다시 적용하지 않기 위한 지름길이고, 페이지 제목이나 검색용 텍스트는 로그에서 파생된 사본이다. 테이블은 14개다. 그중 7개(`doc_ops`, `doc_snapshots`, `users`, `workspaces`, `workspace_members`, `pages`, `page_grants`)를 마이그레이션 V1~V5 로 만들었다. 나머지는 해당 기능을 만들 때 더한다.

## 8.1 연산 로그와 스냅샷

```sql
CREATE TABLE doc_ops (
    doc_id      uuid        NOT NULL,
    rev         bigint      NOT NULL,
    user_id     uuid        NOT NULL,
    client_id   text        NOT NULL,
    client_seq  bigint      NOT NULL,
    ops         jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, rev),
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
```

제약 두 개가 각각 하나의 보장을 맡는다.

| 제약 | 보장 |
| --- | --- |
| `PRIMARY KEY (doc_id, rev)` | 한 문서의 한 리비전은 하나뿐이다. 두 노드가 동시에 쓰면 한쪽이 실패한다 (5.3) |
| `UNIQUE (doc_id, client_id, client_seq)` | 같은 묶음은 한 번만 들어간다 (7.5) |

## 8.2 커밋 시점과 묶음 커밋

ack는 DB 커밋 뒤에만 보낸다. 다른 사람에게 보내는 `remote` 도 커밋 뒤에 보낸다. 커밋되지 않은 편집을 누구도 본 적이 없으므로, 노드가 죽어도 되돌려야 할 화면이 없다.

커밋을 묶음마다 따로 하면 동시 편집자가 많은 문서에서 커밋 횟수가 병목이 된다. 그래서 세션은 커밋이 진행되는 동안 도착한 묶음을 모았다가 다음 커밋 하나에 여러 행으로 넣는다. 한가할 때는 묶음 하나가 바로 커밋되고, 바쁠 때는 저절로 여러 개가 함께 커밋된다. 기다리는 시간을 따로 두지 않는다.

여러 행은 `INSERT ... VALUES (...), (...)` 문 하나로 넣는다. 문장 하나는 자동 커밋에서도 원자적이라 제약에 걸리면 한 행도 남지 않고, BEGIN/COMMIT 왕복이 없다. 묶음 수와 상관없이 DB 왕복은 한 번이다. 문장의 매개변수 한도(65,535) 때문에 1,000행씩 나누고, 나뉘면 한 트랜잭션에 넣는다. 중복 확인은 묶음마다 하지 않고 연결의 첫 묶음에서만 한다(7.5). 그래서 `UNIQUE (doc_id, client_id, client_seq)` 에 걸리는 묶음이 커밋에 섞이지 않는다 (PERF-2, D-38).

## 8.3 스냅샷 규칙

| 항목 | 규칙 |
| --- | --- |
| 만드는 시점 | 마지막 스냅샷 이후 연산이 200개 쌓였을 때, 세션을 메모리에서 내릴 때, 세션을 다른 노드로 양도할 때 |
| 만드는 방법 | 세션의 현재 문서를 불변 사본으로 넘겨 별도 가상 스레드에서 저장한다. 편집 처리는 멈추지 않는다 |
| 문서 열기 | 가장 최근 스냅샷을 읽고, 그 리비전보다 큰 연산을 순서대로 적용한다 |
| 보관 | 최근 3개와 버전 기록이 가리키는 스냅샷을 남기고 나머지는 지운다 |
| 정리 | 스냅샷을 만들 때 삭제 표시 후 30일이 지난 블록을 뺀다 |

"200개"는 시작값이다. 간격을 50, 200, 1,000으로 바꿔 가며 문서 열기 시간과 저장 용량을 재고 값을 정한다.

첫 구현(2단계)에서는 표의 일부만 넣었다. 연산 200개마다와 세션을 내릴 때 스냅샷을 남기고, 최근 3개만 남긴다. 아래 둘은 아직 없다.

- 버전 기록이 가리키는 스냅샷을 남기는 것: `doc_versions` 가 5단계에서 생긴다.
- 세션을 다른 노드로 양도할 때 남기는 것: 양도가 다중 노드(4단계)에서 생긴다.
- 삭제 표시 후 30일이 지난 블록을 빼는 것: 블록에 삭제 시각이 없어서 지금은 언제 지웠는지 알 수 없다. 블록 모델에 삭제 시각을 더하는 작업에서 넣는다.

## 8.4 로그 보관과 용량

- 연산 로그는 90일 동안 전부 보관한다. 오프라인 재접속 허용 기간 7일을 충분히 덮는다.
- 90일이 지난 구간은 하루에 하나의 버전 스냅샷만 남기고 연산을 지운다. 지우는 작업은 매일 새벽에 문서 단위로 나눠 실행한다.
- 연산 하나의 평균 크기와 페이지당 하루 연산 수는 부하 실험에서 재서 용량 계산식에 넣는다. 아직 재지 않았다. 로그를 지우는 작업도 아직 없다.

시간 범위로 파티션을 나누면 오래된 로그를 파티션째 지울 수 있어 편하다. 하지만 PostgreSQL은 파티션 테이블의 유일 제약에 파티션 키를 포함하라고 요구한다. 그러면 (문서, 리비전) 유일 제약이 파티션 전체에 걸쳐 보장되지 않고, 5.3의 마지막 방어선이 깨진다. 그래서 1차에는 파티션을 나누지 않는다. 나중에 나눈다면 `doc_id` 해시 파티션을 쓴다. 이 경우 파티션 키가 기본 키에 들어 있어 제약이 유지된다.

## 8.5 테이블 목록

| 테이블 | 주요 컬럼 | 비고 | 상태 |
| --- | --- | --- | --- |
| `users` | id, email(유일), password_hash, name, created_at | 이메일은 앞뒤 공백을 빼고 소문자로 저장 | V2 |
| `workspaces` | id, name, owner_id(users), created_at | owner_id 는 만든 사람이다. 권한 판단에는 쓰지 않고 `workspace_members.role` 만 본다 | V3 |
| `workspace_members` | workspace_id(workspaces, 지우면 함께), user_id(users), role(OWNER, MEMBER, GUEST), created_at | 기본 키 (workspace_id, user_id). 색인 (user_id) | V3 |
| `workspace_invites` | id, workspace_id, email, role, token_hash, expires_at, accepted_at | 토큰은 해시로 저장 | 계획 (W-1). 지금은 가입한 사용자를 이메일로 바로 넣는다(D-22) |
| `pages` | id, workspace_id(workspaces, 지우면 함께), parent_id(pages), pos(`COLLATE "C"`), title(기본 ''), title_rev(기본 0), restricted(기본 false), created_by(users), created_at, archived_at | id가 곧 문서 ID. title은 루트 블록에서 파생. 색인 (workspace_id, parent_id, pos), (parent_id) WHERE parent_id IS NOT NULL | V3, title_rev 는 V5 |
| `page_grants` | page_id(pages, 지우면 함께), user_id(users), level(VIEW, COMMENT, EDIT, FULL), granted_by(users), created_at | 기본 키 (page_id, user_id). 색인 (user_id) | V4 |
| `share_links` | id, page_id(유일), token_hash, level, expires_at, revoked_at |  | 계획 (S-4) |
| `doc_ops` | 8.1 참고 | doc_id 는 페이지 ID 지만 외래 키를 걸지 않는다(V3 이전 문서가 남아 있다) | V1 |
| `doc_snapshots` | 8.1 참고 |  | V1 |
| `doc_versions` | id, doc_id, rev, kind(AUTO, NAMED, RESTORE), name, created_by, created_at | 버전 기록 목록 | 계획 (5단계) |
| `comment_threads` | id, doc_id, created_by, status(OPEN, RESOLVED), quote, orphaned | quote는 댓글을 달 당시의 원문 | 계획 (5단계) |
| `comments` | id, thread_id, author_id, body, created_at, edited_at, deleted_at |  | 계획 (5단계) |
| `notifications` | id, user_id, type, page_id, ref_id, dedupe_key, read_at | 유일 (user_id, dedupe_key) | 계획 (5단계) |
| `page_search` | page_id, workspace_id, title, body, rev | pg_bigm GIN 인덱스 | 계획 (5단계) |

Redis 에는 Refresh 토큰, Access 토큰 차단 목록(로그아웃한 jti, 모든 세션을 끊은 사용자의 시각), 로그인 실패 횟수, 1회용 티켓을 둔다. 소유 리스는 다중 노드(4단계)에서 더한다. 권한 캐시는 두지 않는다(D-11, 9.4).

마이그레이션은 Flyway 로 한다. 앱의 연결 풀과 따로 연결해, 풀의 소켓 시간 한도(10초)가 긴 마이그레이션을 끊지 않게 한다(D-33).

| 마이그레이션 | 내용 |
| --- | --- |
| V1 | `doc_ops`, `doc_snapshots` |
| V2 | `users` |
| V3 | `workspaces`, `workspace_members`, `pages`. 이미 있던 문서를 첫 작성자의 "가져온 문서" 워크스페이스로 옮긴다(8.6, D-09) |
| V4 | `page_grants` |
| V5 | `pages.title_rev` (D-21) |

## 8.6 페이지 트리 저장

`pages` 는 `parent_id` 와 `pos` 로 트리를 표현한다. 블록과 같은 분수 인덱스를 써서, 페이지를 옮겨도 그 페이지 한 행만 고친다.

- `pos` 는 `COLLATE "C"` 로 둔다. 분수 인덱스 키를 Java 의 `String.compareTo` 와 같은 순서(바이트 순서)로 비교해야 한다. 기본 정렬 규칙(예: `en_US.UTF-8`)은 대소문자를 섞어 정렬해 순서가 달라진다.
- 페이지를 만들 때도 워크스페이스 단위 advisory lock 안에서 형제의 키를 읽고 새 키를 정한다. 동시에 같은 자리에 만들어도 키가 겹치지 않는다. 보관된 형제도 자리를 차지하므로, 나중에 복원해도 키가 겹치지 않는다.
- 페이지 ID 가 곧 문서 ID 다. 문서는 처음 열 때 빈 문서로 생긴다. `pages` 가 생기기 전(V3 이전)에 만든 문서는 V3 가 첫 연산을 쓴 사용자의 "가져온 문서" 워크스페이스에 최상위 페이지로 옮긴다. 첫 작성자가 `users` 에 없는 문서는 옮기지 않고 연산 로그를 그대로 둔다.
- `title` 은 루트 블록 글이다. 문서 세션이 루트 글이 바뀐 커밋 뒤와 세션을 올릴 때 알리고, `title_rev` 가 더 클 때만 바꾼다(늦게 도착한 알림이 새 제목을 덮지 않는다, D-21).
- 하위 목록은 사이드바에서 펼칠 때 한 단계씩 읽는다. 목록의 `hasChildren` 은 권한을 보지 않고 센다(D-12).
- 이동할 때는 새 부모의 조상을 재귀 CTE로 거슬러 올라가 자기 자신이 나오는지 검사한다. 검사와 갱신은 워크스페이스 단위 advisory lock 안에서 한다. 두 사람이 동시에 서로의 아래로 옮기는 경우를 막기 위해서다(#80, D-39). 깊이 제한은 아직 검사하지 않는다.
- 휴지통으로 보내면 `archived_at` 만 채운다. 하위 페이지는 부모를 따라 보이지 않게 된다. 꺼낼 때 부모가 아직 휴지통에 있으면 최상위 맨 뒤로 간다(#80, D-39). 30일 뒤 하위와 연산 로그까지 실제로 지우는 배치는 아직 없다.
