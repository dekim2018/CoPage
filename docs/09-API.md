# 11. REST API 목록

REST는 본문 편집을 다루지 않는다. 본문은 WebSocket으로만 바뀌고, REST는 그 주변(계정, 트리, 권한, 기록, 댓글, 검색, 알림)을 맡는다. 엔드포인트는 46개로 설계했다. 지금 만든 것은 25개이고 11.0 에 모았다. 11.1~11.4 의 표는 설계 전체이고, "상태" 칸이 "계획" 인 것은 아직 없다.

오류 응답은 RFC 9457 형식(`application/problem+json`)으로 통일한다. Spring의 `ProblemDetail` 을 쓴다.

## 11.0 지금 있는 엔드포인트

"인증" 의 "토큰" 은 `Authorization: Bearer` 의 Access 토큰이다. 토큰이 없거나 틀리면 401 이다. 모든 경로는 저장소(Redis, PostgreSQL)에 닿지 못하면 503 과 `Retry-After: 5` 를 준다(9.7). 입력 검증 실패는 400 이다.

| 메서드 | 경로 | 인증과 권한 | 성공 | 주요 오류 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/signup` | 없음 | 201, 사용자 | 409 이미 가입한 이메일. 400 비밀번호가 8~64자가 아니거나 UTF-8 로 72 바이트를 넘음 |
| POST | `/api/auth/login` | 없음 | 200, Access 토큰과 사용자. Refresh 토큰은 쿠키(`copage_refresh`, 경로 `/api/auth`) | 401 이메일이나 비밀번호가 틀림(어느 쪽인지 알리지 않는다). 429 실패가 많아 잠김 |
| POST | `/api/auth/refresh` | Refresh 쿠키, Origin 검사 | 200, 새 Access 토큰과 새 쿠키 | 401 쿠키가 없거나 틀림(쿠키를 지운다). 409 동시 재발급(쿠키를 지우지 않는다). 403 허용하지 않은 Origin |
| POST | `/api/auth/logout` | Refresh 쿠키, Origin 검사. Access 토큰이 있으면 남은 시간 동안 막는다 | 204 | 403 허용하지 않은 Origin |
| GET | `/api/me` | 토큰 | 200, 내 정보 |  |
| PATCH | `/api/me` | 토큰 | 200, 이름을 바꾼 내 정보 | 400 이름이 비었거나 50자를 넘음 |
| GET | `/api/workspaces` | 토큰 | 200, 내가 속한 워크스페이스와 내 역할 |  |
| POST | `/api/workspaces` | 토큰 | 201, 만든 워크스페이스(내 역할 OWNER) | 400 이름이 비었거나 100자를 넘음 |
| GET | `/api/workspaces/{wsId}/members` | OWNER 또는 MEMBER | 200, 멤버 목록 | 404 속하지 않은 워크스페이스. 403 GUEST |
| POST | `/api/workspaces/{wsId}/members` | OWNER | 201, 넣은 멤버. 가입한 사용자를 이메일로 바로 넣는다. 역할은 MEMBER 또는 GUEST | 403 OWNER 가 아님. 400 OWNER 로 넣으려 함. 404 그 이메일로 가입한 사용자가 없음. 409 이미 멤버 |
| PATCH | `/api/workspaces/{wsId}/members/{userId}` | OWNER | 204 | 403 OWNER 가 아님. 404 멤버가 아님. 409 마지막 OWNER 를 내림 |
| DELETE | `/api/workspaces/{wsId}/members/{userId}` | OWNER | 204. 그 워크스페이스에서 받은 페이지 권한도 지운다 | 403, 404, 409 위와 같음 |
| GET | `/api/workspaces/{wsId}/pages?parentId=` | 워크스페이스의 모든 역할. 목록은 내가 VIEW 이상인 페이지만 | 200, 한 단계 하위 페이지(id, parentId, title, hasChildren, level) | 404 속하지 않은 워크스페이스, 없거나 볼 수 없는 부모 |
| POST | `/api/workspaces/{wsId}/pages` | 부모의 EDIT. 최상위면 역할의 기본 권한이 EDIT 이상(OWNER, MEMBER) | 201, 만든 페이지. 본문 `{parentId, afterId}` 는 생략할 수 있다 | 404 속하지 않은 워크스페이스나 없는 부모. 403 등급이 모자람 |
| GET | `/api/pages/{pageId}` | VIEW | 200, 제목, restricted, 내 등급, 경로(볼 수 있는 조상만) | 404 없거나 휴지통이거나 속하지 않음. 403 속하지만 등급이 없음 |
| GET | `/api/pages/{pageId}/grants` | FULL | 200, 이 페이지에 준 권한과 물려받는 권한(`inheritedFrom`) | 404 없거나 볼 수 없는 페이지. 403 FULL 이 아님 |
| PUT | `/api/pages/{pageId}/grants/{userId}` | FULL | 200, 준 권한. 멤버가 아니면 GUEST 로 넣는다 | 404 없는 사용자. 그 밖은 위와 같음 |
| POST | `/api/pages/{pageId}/grants` | FULL | 200, 준 권한. 본문 `{email, level}` | 404 그 이메일로 가입한 사용자가 없음. 그 밖은 위와 같음 |
| DELETE | `/api/pages/{pageId}/grants/{userId}` | FULL | 204 | 404 이 페이지에 그 사용자에게 준 권한이 없음. 그 밖은 위와 같음 |
| PUT | `/api/pages/{pageId}/restricted` | FULL | 200, `{restricted}`. 켜는 사람이 OWNER 가 아니면 그 페이지에 자신의 FULL 을 남긴다 | 위와 같음 |
| POST | `/api/pages/{pageId}/move` | 그 페이지의 FULL + 새 부모의 EDIT(최상위면 역할의 기본 권한) | 200, 옮긴 페이지(GET 과 같은 모양). 본문 `{parentId, afterId}`, 둘 다 생략하면 최상위 맨 뒤 | 400 자기 자신이나 하위 아래로, afterId 가 새 부모의 하위가 아님. 404 없거나 휴지통이거나 새 부모를 볼 수 없음. 403 등급이 모자람 |
| DELETE | `/api/pages/{pageId}` | FULL | 204, 휴지통으로. 하위는 부모를 따라 보이지 않는다 | 404 없거나 이미 휴지통. 403 FULL 이 아님 |
| POST | `/api/pages/{pageId}/restore` | 휴지통에 가기 전 FULL | 200, 꺼낸 페이지. 부모가 아직 휴지통에 있으면 최상위 맨 뒤 | 404 휴지통에 없거나 속하지 않음. 403 등급이 모자람 |
| GET | `/api/workspaces/{wsId}/trash` | 워크스페이스의 모든 역할 | 200, 휴지통의 페이지(id, parentId, title, archivedAt, canRestore). 보내기 전에 볼 수 있던 페이지만 | 404 속하지 않은 워크스페이스 |
| POST | `/api/docs/{docId}/ws-ticket` | VIEW | 200, `{ticket, role}`. 티켓은 30초 1회용 | 404 페이지가 없거나 속하지 않음. 403 등급이 없음 |

권한을 바꾸는 요청(멤버 역할, 내보내기, 페이지 권한, restricted, 페이지 옮기기, 휴지통으로 보내기와 꺼내기)은 커밋한 뒤 열린 편집 연결에 1초 안에 적용된다(9.4). 옮기면 새 부모의 권한을 물려받으므로, 제한된 페이지 밖으로 옮기면 더 넓게 보일 수 있다(D-39).

API 포트에는 `/actuator` 가 없다. 지표와 상태 확인은 관리 포트(기본 8081)의 `/actuator/prometheus`, `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` 다(13.4).

## 11.1 인증

| 메서드 | 경로 | 설명 | 상태 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 가입 | 구현 |
| POST | `/api/auth/login` | 로그인. Access 토큰은 본문, Refresh 토큰은 쿠키 | 구현 |
| POST | `/api/auth/refresh` | 토큰 재발급과 회전 | 구현 |
| POST | `/api/auth/logout` | 로그아웃 | 구현 |
| GET | `/api/me` | 내 정보 | 구현 |
| PATCH | `/api/me` | 이름 수정 | 구현 |

## 11.2 워크스페이스와 페이지

| 메서드 | 경로 | 설명 | 필요 권한 | 상태 |
| --- | --- | --- | --- | --- |
| GET | `/api/workspaces` | 내가 속한 워크스페이스 | 로그인 | 구현 |
| POST | `/api/workspaces` | 생성 | 로그인 | 구현 |
| GET | `/api/workspaces/{wsId}/members` | 멤버 목록 | MEMBER | 구현 |
| POST | `/api/workspaces/{wsId}/members` | 가입한 사용자를 이메일로 바로 넣는다. 역할은 MEMBER 또는 GUEST. 초대(아래 invites)가 생기기 전까지 쓰는 임시 경로 | OWNER | 구현 |
| POST | `/api/workspaces/{wsId}/invites` | 이메일로 초대 | OWNER | 계획 |
| POST | `/api/invites/{token}/accept` | 초대 수락 | 로그인 | 계획 |
| PATCH | `/api/workspaces/{wsId}/members/{userId}` | 역할 변경. 마지막 OWNER 는 내릴 수 없다(409) | OWNER | 구현 |
| DELETE | `/api/workspaces/{wsId}/members/{userId}` | 내보내기. 그 워크스페이스에서 받은 페이지 권한도 지운다. 마지막 OWNER 는 내보낼 수 없다(409) | OWNER | 구현 |
| GET | `/api/workspaces/{wsId}/pages?parentId=` | 한 단계 하위 페이지 목록. 권한 없는 페이지는 빼고 준다 | MEMBER 또는 GUEST | 구현 |
| POST | `/api/workspaces/{wsId}/pages` | 페이지 생성 (부모, 위치 지정) | 부모의 EDIT | 구현 |
| GET | `/api/pages/{pageId}` | 제목, restricted, 경로(볼 수 있는 조상만), 내 권한 등급 | VIEW | 구현 |
| POST | `/api/pages/{pageId}/move` | 부모와 순서 변경 | FULL + 새 부모의 EDIT | 구현(#80) |
| DELETE | `/api/pages/{pageId}` | 휴지통으로 | FULL | 구현(#80) |
| POST | `/api/pages/{pageId}/restore` | 휴지통에서 복구 | FULL | 구현(#80) |
| GET | `/api/workspaces/{wsId}/trash` | 휴지통 목록 | 워크스페이스의 모든 역할, 보내기 전에 볼 수 있던 페이지만 | 구현(#80) |
| GET | `/api/workspaces/{wsId}/events?ticket=` | 사이드바용 SSE (트리 변경) | MEMBER 또는 GUEST | 계획 |

워크스페이스에 속하지 않은 사용자에게는 워크스페이스가 있는지도 알리지 않는다. 없는 워크스페이스와 같은 404 를 준다. 페이지 접근은 9.4 의 등급으로 판단한다. 목록은 내가 VIEW 이상인 페이지만 주고 각 페이지에 내 등급(`level`)을 붙인다. 볼 수 없는 페이지는 없는 페이지와 같은 404 다. 페이지를 만들려면 부모의 EDIT(최상위면 역할의 기본 권한)가 필요하다. 목록의 `hasChildren` 은 권한을 보지 않고 센다. 볼 수 없는 하위 페이지만 있어도 `true` 이고, 펼치면 빈 목록이 올 수 있다. 페이지의 `title` 은 루트 블록(제목 줄)의 글이다. 편집이 커밋된 뒤 따로 쓰므로 목록에는 아주 조금 늦게 보인다. 제목을 동기화하기 전에 만들고 그 뒤 한 번도 열지 않은 페이지는 빈 문자열이다.

## 11.3 공유와 권한

| 메서드 | 경로 | 설명 | 필요 권한 | 상태 |
| --- | --- | --- | --- | --- |
| GET | `/api/pages/{pageId}/grants` | 이 페이지에 준 권한과 경계까지 물려받는 권한(`inheritedFrom`) | FULL | 구현 |
| PUT | `/api/pages/{pageId}/grants/{userId}` | 사용자 권한 부여 또는 변경. 워크스페이스 멤버가 아니면 GUEST 로 넣는다 | FULL | 구현 |
| POST | `/api/pages/{pageId}/grants` | 이메일로 부여(`{email, level}`). 공유 창이 쓴다 | FULL | 구현 |
| DELETE | `/api/pages/{pageId}/grants/{userId}` | 이 페이지에 준 권한 회수 | FULL | 구현 |
| PUT | `/api/pages/{pageId}/restricted` | 상속 끊기 또는 다시 잇기. 끊는 사람이 OWNER 가 아니면 그 페이지에 자신의 FULL 을 남긴다 | FULL | 구현 |
| PUT | `/api/pages/{pageId}/share-link` | 링크 공유 켜기, 등급과 만료일 설정 | FULL | 계획 |
| DELETE | `/api/pages/{pageId}/share-link` | 링크 공유 끄기 | FULL | 계획 |
| GET | `/api/share/{token}` | 링크로 페이지 정보 조회 | 없음 | 계획 |
| POST | `/api/share/{token}/ws-ticket` | 링크 방문자용 WebSocket 티켓. 비로그인이면 임시 이름(예: 익명 수달)을 붙인다 | 없음 | 계획 |

## 11.4 협업, 버전, 댓글, 검색, 알림

| 메서드 | 경로 | 설명 | 필요 권한 | 상태 |
| --- | --- | --- | --- | --- |
| POST | `/api/docs/{docId}/ws-ticket` | WebSocket 티켓 발급. 티켓에 내 등급을 담는다. 페이지가 없거나 속하지 않으면 404, 등급이 없으면 403 | VIEW | 구현 |
| POST | `/api/docs/{docId}/conflict-copy` | 오프라인 편집본을 복사본 페이지로 저장 | 부모의 EDIT | 계획 |
| GET | `/api/docs/{docId}/versions?before=` | 버전 목록 (커서 방식 페이지) | VIEW | 계획 |
| GET | `/api/docs/{docId}/versions/{rev}` | 그 시점의 문서 내용 | VIEW | 계획 |
| POST | `/api/docs/{docId}/versions` | 현재 시점에 이름 붙이기 | EDIT | 계획 |
| POST | `/api/docs/{docId}/versions/{rev}/restore` | 그 시점으로 복원 | FULL | 계획 |
| GET | `/api/docs/{docId}/threads?status=` | 댓글 스레드 목록 | VIEW | 계획 |
| POST | `/api/docs/{docId}/threads` | 스레드 생성 (첫 댓글, 원문 발췌 포함) | COMMENT | 계획 |
| POST | `/api/threads/{threadId}/comments` | 답글 | COMMENT | 계획 |
| PATCH | `/api/comments/{commentId}` | 내 댓글 수정 | 작성자 | 계획 |
| DELETE | `/api/comments/{commentId}` | 내 댓글 삭제 | 작성자 또는 FULL | 계획 |
| POST | `/api/threads/{threadId}/resolve` | 해결 처리. `reopen` 으로 다시 연다 | COMMENT | 계획 |
| GET | `/api/workspaces/{wsId}/search?q=` | 제목과 본문 검색 | MEMBER 또는 GUEST | 계획 |
| GET | `/api/notifications?before=` | 알림 목록 | 로그인 | 계획 |
| POST | `/api/notifications/read` | 읽음 처리 (여러 개) | 로그인 | 계획 |
| GET | `/api/notifications/stream?ticket=` | 알림 SSE | 로그인 | 계획 |

SSE 두 개도 WebSocket과 같은 이유로 1회용 티켓을 쓴다. 티켓은 `POST /api/stream-ticket` 으로 받는다.
