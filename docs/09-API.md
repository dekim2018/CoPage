# 11. REST API 목록

REST는 본문 편집을 다루지 않는다. 본문은 WebSocket으로만 바뀌고, REST는 그 주변(계정, 트리, 권한, 기록, 댓글, 검색, 알림)을 맡는다. 엔드포인트는 46개다.

오류 응답은 RFC 9457 형식(`application/problem+json`)으로 통일한다. Spring의 `ProblemDetail` 을 쓴다.

## 11.1 인증

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 가입 |
| POST | `/api/auth/login` | 로그인. Access 토큰은 본문, Refresh 토큰은 쿠키 |
| POST | `/api/auth/refresh` | 토큰 재발급과 회전 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/me` | 내 정보 |
| PATCH | `/api/me` | 이름 수정 |

## 11.2 워크스페이스와 페이지

| 메서드 | 경로 | 설명 | 필요 권한 |
| --- | --- | --- | --- |
| GET | `/api/workspaces` | 내가 속한 워크스페이스 | 로그인 |
| POST | `/api/workspaces` | 생성 | 로그인 |
| GET | `/api/workspaces/{wsId}/members` | 멤버 목록 | MEMBER |
| POST | `/api/workspaces/{wsId}/members` | 가입한 사용자를 이메일로 바로 넣는다. 역할은 MEMBER 또는 GUEST. 초대(아래 invites)가 생기기 전까지 쓰는 임시 경로 | OWNER |
| POST | `/api/workspaces/{wsId}/invites` | 이메일로 초대 | OWNER |
| POST | `/api/invites/{token}/accept` | 초대 수락 | 로그인 |
| PATCH | `/api/workspaces/{wsId}/members/{userId}` | 역할 변경. 마지막 OWNER 는 내릴 수 없다(409) | OWNER |
| DELETE | `/api/workspaces/{wsId}/members/{userId}` | 내보내기. 그 워크스페이스에서 받은 페이지 권한도 지운다. 마지막 OWNER 는 내보낼 수 없다(409) | OWNER |
| GET | `/api/workspaces/{wsId}/pages?parentId=` | 한 단계 하위 페이지 목록. 권한 없는 페이지는 빼고 준다 | MEMBER 또는 GUEST |
| POST | `/api/workspaces/{wsId}/pages` | 페이지 생성 (부모, 위치 지정) | 부모의 EDIT |
| GET | `/api/pages/{pageId}` | 제목, 경로(볼 수 있는 조상만), 내 권한 등급 | VIEW |
| POST | `/api/pages/{pageId}/move` | 부모와 순서 변경 | FULL + 새 부모의 EDIT |
| DELETE | `/api/pages/{pageId}` | 휴지통으로 | FULL |
| POST | `/api/pages/{pageId}/restore` | 휴지통에서 복구 | FULL |
| GET | `/api/workspaces/{wsId}/trash` | 휴지통 목록 | MEMBER |
| GET | `/api/workspaces/{wsId}/events?ticket=` | 사이드바용 SSE (트리 변경) | MEMBER 또는 GUEST |

워크스페이스에 속하지 않은 사용자에게는 워크스페이스가 있는지도 알리지 않는다. 없는 워크스페이스와 같은 404 를 준다. 페이지 접근은 9.4 의 등급으로 판단한다. 목록은 내가 VIEW 이상인 페이지만 주고 각 페이지에 내 등급(`level`)을 붙인다. 볼 수 없는 페이지는 없는 페이지와 같은 404 다. 페이지를 만들려면 부모의 EDIT(최상위면 역할의 기본 권한)가 필요하다. 페이지 권한 부여와 restricted 변경 API(11.3)는 아직 없다. 목록의 `hasChildren` 은 권한을 보지 않고 센다. 볼 수 없는 하위 페이지만 있어도 `true` 이고, 펼치면 빈 목록이 올 수 있다. 페이지의 `title` 은 루트 블록(제목 줄)의 글이다. 편집이 커밋된 뒤 따로 쓰므로 목록에는 아주 조금 늦게 보인다. 제목을 동기화하기 전에 만들고 그 뒤 한 번도 열지 않은 페이지는 빈 문자열이다.

## 11.3 공유와 권한

| 메서드 | 경로 | 설명 | 필요 권한 |
| --- | --- | --- | --- |
| GET | `/api/pages/{pageId}/grants` | 이 페이지에 준 권한과 경계까지 물려받는 권한(`inheritedFrom`) | FULL |
| PUT | `/api/pages/{pageId}/grants/{userId}` | 사용자 권한 부여 또는 변경. 워크스페이스 멤버가 아니면 GUEST 로 넣는다 | FULL |
| POST | `/api/pages/{pageId}/grants` | 이메일로 부여(`{email, level}`). 공유 창이 쓴다 | FULL |
| DELETE | `/api/pages/{pageId}/grants/{userId}` | 이 페이지에 준 권한 회수 | FULL |
| PUT | `/api/pages/{pageId}/restricted` | 상속 끊기 또는 다시 잇기. 끊는 사람이 OWNER 가 아니면 그 페이지에 자신의 FULL 을 남긴다 | FULL |
| PUT | `/api/pages/{pageId}/share-link` | 링크 공유 켜기, 등급과 만료일 설정 | FULL |
| DELETE | `/api/pages/{pageId}/share-link` | 링크 공유 끄기 | FULL |
| GET | `/api/share/{token}` | 링크로 페이지 정보 조회 | 없음 |
| POST | `/api/share/{token}/ws-ticket` | 링크 방문자용 WebSocket 티켓. 비로그인이면 임시 이름(예: 익명 수달)을 붙인다 | 없음 |

## 11.4 협업, 버전, 댓글, 검색, 알림

| 메서드 | 경로 | 설명 | 필요 권한 |
| --- | --- | --- | --- |
| POST | `/api/docs/{docId}/ws-ticket` | WebSocket 티켓 발급. 티켓에 내 등급을 담는다. 페이지가 없거나 속하지 않으면 404, 등급이 없으면 403 | VIEW |
| POST | `/api/docs/{docId}/conflict-copy` | 오프라인 편집본을 복사본 페이지로 저장 | 부모의 EDIT |
| GET | `/api/docs/{docId}/versions?before=` | 버전 목록 (커서 방식 페이지) | VIEW |
| GET | `/api/docs/{docId}/versions/{rev}` | 그 시점의 문서 내용 | VIEW |
| POST | `/api/docs/{docId}/versions` | 현재 시점에 이름 붙이기 | EDIT |
| POST | `/api/docs/{docId}/versions/{rev}/restore` | 그 시점으로 복원 | FULL |
| GET | `/api/docs/{docId}/threads?status=` | 댓글 스레드 목록 | VIEW |
| POST | `/api/docs/{docId}/threads` | 스레드 생성 (첫 댓글, 원문 발췌 포함) | COMMENT |
| POST | `/api/threads/{threadId}/comments` | 답글 | COMMENT |
| PATCH | `/api/comments/{commentId}` | 내 댓글 수정 | 작성자 |
| DELETE | `/api/comments/{commentId}` | 내 댓글 삭제 | 작성자 또는 FULL |
| POST | `/api/threads/{threadId}/resolve` | 해결 처리. `reopen` 으로 다시 연다 | COMMENT |
| GET | `/api/workspaces/{wsId}/search?q=` | 제목과 본문 검색 | MEMBER 또는 GUEST |
| GET | `/api/notifications?before=` | 알림 목록 | 로그인 |
| POST | `/api/notifications/read` | 읽음 처리 (여러 개) | 로그인 |
| GET | `/api/notifications/stream?ticket=` | 알림 SSE | 로그인 |

SSE 두 개도 WebSocket과 같은 이유로 1회용 티켓을 쓴다. 티켓은 `POST /api/stream-ticket` 으로 받는다.
