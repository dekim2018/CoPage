# 보안 진단 결과

아키텍처 진단의 보안 항목(SEC-1~SEC-7)과 그 처리 결과다. 항목마다 문제, 고친 것, PR, 그것을 지키는 시험, 남은 위험을 적는다. 전체 판정 방법은 [ARCHITECTURE-REVIEW.md](ARCHITECTURE-REVIEW.md) 2장에 있다. 설계 문서는 [07-SECURITY.md](../07-SECURITY.md) 다.

## 1. 요약

| ID | 심각도 | 문제 | 상태 | PR | 결정 |
| --- | --- | --- | --- | --- | --- |
| SEC-1 | HIGH | 권한 검사가 없다 | 고침(노드 1대 기준) | #25, #29, #31, #33, #34, #36 | D-02, D-09~D-20, D-22 |
| SEC-2 | MEDIUM | Redis 장애 시 동작이 없다 | 고침 | #23 | D-03, D-08 |
| SEC-3 | MEDIUM | presence 빈도 제한이 없다 | 고침 | #21 | D-07 |
| SEC-4 | LOW | Refresh 의 Origin 검사가 CORS 에 기댄다 | 고침 | #45 | D-28 |
| SEC-5 | LOW | JWT 에 iss, aud 가 없다 | 고침 | #45 | D-28 |
| SEC-6 | LOW | 가입이 409 로 이메일 존재를 알려 준다 | 정책으로 유지 | #45 | D-28 |
| SEC-7 | LOW | WebSocket 티켓이 주소에 실린다 | 정책으로 유지 | #45 | D-28 |
| OPS-2 | LOW | CI 에 비밀키, 의존성 검사가 없다 | 고침 | #53 | D-32 |

공개 배포를 막던 유일한 HIGH 는 SEC-1 이었다. 노드 1대에서는 닫혔다. 다중 노드에서 권한 회수를 다른 노드의 세션에 전하는 일은 남아 있다(5장).

## 2. SEC-1 권한

### 문제

로그인한 사용자는 어떤 문서 ID 로든 `POST /api/docs/{docId}/ws-ticket` 을 불러 EDIT 티켓을 받았다(진단 시점 `TicketController.java:33`). 문서 ID 는 UUIDv7 이라 추측은 어렵지만, 링크가 새면 누구나 읽고 고칠 수 있었다. `AuthFlowTest` 의 티켓 발급이 아무 UUID 로 성공해 재현되었다.

### 고친 것

설계 9.4 를 앞당겨 PR 여섯 개로 만들었다(D-02).

| PR | 내용 |
| --- | --- |
| #25 | V3 마이그레이션: `workspaces`, `workspace_members`, `pages`. 페이지 ID 가 문서 ID. 기존 문서는 첫 작성자의 "가져온 문서" 워크스페이스로 옮긴다(D-09) |
| #29 | V4 `page_grants`, permission 모듈. 재귀 CTE 하나로 사슬을 읽고 `PermissionRules` 로 계산한다. 티켓은 페이지가 없거나 휴지통이거나 속하지 않으면 404, 등급이 없으면 403. 티켓에 계산한 등급을 싣는다 |
| #31 | 부여, 회수, restricted, 멤버 역할 변경과 내보내기 API. 변경 알림 포트 `PermissionChangeListener` |
| #33 | 열린 연결에 1초 안에 적용(S-5). 등급이 바뀌면 `role` 메시지, 잃으면 4003. 화면은 EDIT 미만이면 읽기 전용 |
| #34 | 페이지 제목을 루트 블록에서 채운다(화면의 전제) |
| #36 | 화면: 워크스페이스, 페이지 트리, 공유 창, 멤버 화면. 편집기 경로 `/w/:workspaceId/p/:pageId` |

권한 계산 규칙(9.4):

1. 페이지에서 루트로 올라가며 restricted 가 켜진 첫 페이지가 경계다
2. 경계가 없으면 워크스페이스 역할 기본 권한(OWNER FULL, MEMBER EDIT, GUEST 없음), 있으면 없음에서 시작한다. OWNER 는 늘 FULL
3. 페이지부터 경계까지의 내 부여 중 가장 높은 등급을 더한다

본문 연산은 세션이 연결의 등급으로 다시 검사한다(9.5, `ChangePolicy`). VIEW 의 모든 연산과, COMMENT 의 댓글 표시(retain 의 댓글 서식)가 아닌 연산은 `nack(code=permission)` 이다(`ChangePolicy.java:123-131`).

### 결정

| 결정 | 내용 |
| --- | --- |
| D-10 | 없는 페이지, 속하지 않은 워크스페이스, 휴지통 페이지는 모두 404. 존재 여부를 흘리지 않는다 |
| D-11 | 권한 캐시를 두지 않는다. 캐시 무효화를 잘못 잡으면 회수한 권한이 남는다 |
| D-12 | 목록의 `hasChildren` 은 권한을 보지 않고 센다. 볼 수 없는 하위 페이지의 존재(제목과 ID 는 아님)가 드러난다 |
| D-13 | 워크스페이스에 없는 사용자에게 페이지 권한을 주면 GUEST 로 넣는다 |
| D-14 | 물려받은 FULL 로 restricted 를 켠 사람에게 그 페이지의 FULL 을 남긴다(스스로 잠기지 않게) |
| D-15 | 마지막 OWNER 는 내보내거나 내릴 수 없다. 워크스페이스 단위 advisory lock 안에서 확인한다 |
| D-16 | 워크스페이스에서 내보내면 그 워크스페이스의 페이지 권한도 지운다 |
| D-17, D-19 | 변경 알림은 커밋 뒤 프로세스 안 포트로. 다시 계산은 세션 실행 흐름 밖에서 |
| D-18 | COMMENT 는 편집기에서 읽기 전용 |
| D-20 | 등급이 내려간 뒤 도착한 묶음은 nack 로 버려진다. 살리기(D-27)도 permission 은 다시 보내지 않는다 |

### 시험

| 시험 | 지키는 것 |
| --- | --- |
| `PermissionRulesTest` | 9.4 계산 순서(기본 권한, 부여, 경계, 가장 가까운 경계, OWNER 예외) |
| `PagePermissionsTest` (실제 PostgreSQL) | 없는 페이지와 속하지 않은 사람은 같은 NotFound, 다른 가지로 이어지지 않음, 휴지통 조상, 경로에 볼 수 있는 조상만, 하위 목록 계산이 하나씩 계산한 것과 같음 |
| `PermissionFlowTest` (실제 서버) | 티켓 404/403 과 등급, welcome 의 role |
| `GrantFlowTest` (실제 서버) | FULL 만 바꾼다, GUEST 편입, `inheritedFrom`, 회수하면 티켓 403, D-14, 역할 변경과 내보내기, 마지막 OWNER 409, 두 OWNER 가 서로를 동시에 내려도 하나 남음 |
| `JdbcWorkspaceRepositoryTest` | 역할 변경이 워크스페이스 잠금을 잡는지 결정적으로 확인. 잠금을 빼면 실패한다(처음의 HTTP 동시 요청 시험은 잠금을 뺀 변이에서도 통과해 바꿨다) |
| `LiveRevocationTest` (실제 서버, WebSocket) | 내리기 `role` 9ms, 회수 4003 16ms, 상위 제한 4003 16ms, 내보내기 4003 9ms(노드 1대, 로컬). 리스너를 끄는 변이에서 실패한다 |
| `DocumentSessionTest` | 내려가면 그 연결에만 role, 이후 묶음 nack, 계산 실패 시 바꾸지 않음 |
| `V3MigrationTest` | 기존 문서를 첫 작성자 기준으로 옮기고, users 에 없는 작성자의 문서는 옮기지 않고 연산 로그를 남긴다 |
| e2e | 권한을 내리면 상대 편집기가 1초 안에 읽기 전용, 회수하면 닫힘. 속하지 않은 사람은 404 화면 |

### 남은 위험

- 다중 노드: 권한 변경 알림은 같은 프로세스의 세션에만 닿는다(D-17). 노드가 여럿이면 다른 노드의 열린 연결은 다음 재접속까지 예전 등급으로 편집한다. 로드맵 4단계에서 Redis pub/sub 으로 넓힌다
- 다시 계산이 실패하면(DB 오류) 등급을 바꾸지 않는다(D-19). 그 연결은 다음 재접속 때 새 티켓으로 바른 등급을 받는다. 그 사이 회수된 사람이 편집할 수 있다
- D-12: 볼 수 없는 하위 페이지의 존재가 `hasChildren` 으로 드러난다
- D-14: 자동으로 남긴 FULL 은 제한을 풀거나 조상 FULL 을 회수해도 남는다. 권한 목록에는 보인다
- 멤버 추가(가입한 사용자를 이메일로 바로 넣는 임시 경로)는 가입 여부를 OWNER 에게 알려 준다(D-22). 초대 메일(W-1)로 바꾸면 없어진다
- 공유 링크(9.4 의 4번, S-4)는 아직 없다

## 3. SEC-2 Redis 장애

### 문제

진단 때는 정책이 없었다. 고치기 전 코드로 Redis 를 일시 정지해 확인한 결과(#23):

- `/api/me` 가 500. 토큰 검증기(`isRevoked`)가 보안 필터 안에서 `QueryTimeoutException` 을 던졌다
- Redis 명령 시간 한도 기본 60초. 요청이 1분 동안 매달렸다
- 화면은 409 가 아닌 재발급 실패를 모두 로그아웃으로 봤다. 편집 중 Access 토큰이 만료되면 로그인 화면으로 갔다

### 고친 것

- 막힘(fail-closed, D-03). 토큰 확인, 로그인, 재발급, 로그아웃, 티켓 발급, 핸드셰이크가 503 + `Retry-After: 5`. 가입은 PostgreSQL 만 써서 영향이 없다. 열린 편집 연결은 Redis 를 쓰지 않아 계속된다
- `StoreUnavailableFilter`: 보안 필터보다 바깥에서, 원인 사슬에 연결 실패(`DataAccessResourceFailureException`)나 응답 시간 초과(`QueryTimeoutException`)가 있을 때만 503. 다른 데이터 오류는 500 그대로
- Redis 명령과 연결 시간 한도 2초, PostgreSQL 연결 풀 대기 3초
- 화면: 재발급이 5xx 이거나 서버에 닿지 못하면 Access 토큰을 지우지 않는다
- Refresh 회전(D-08): 시간 초과로 포기한 명령도 Redis 가 돌아오면 실행된다는 것을 일시 정지 실험으로 확인했다. 늦은 회전이 세션을 잃게 하므로, 주인과 사용자를 먼저 읽고, 옛 토큰 표시와 새 토큰 저장을 스크립트 하나로 하고, 기한이 지난 실행은 아무것도 바꾸지 않는다
- 버린 안: 열림(fail-open). 로그아웃한 토큰과 재사용이 드러난 사용자의 토큰이 장애 동안 다시 통한다

### 시험

- `RedisOutageTest`: 이 시험만의 Redis 컨테이너를 일시 정지한다. 위 경로가 503, 가입 201, 열린 연결의 편집은 ack 까지 확정, 되살린 뒤 같은 쿠키로 재발급 200. 고치기 전 코드에서 500 으로 실패하는 것을 먼저 확인했다. 생존 검사(liveness) 200, 묶은 health 503 도 본다
- `StoreUnavailableFilterTest`: 감싼 예외도 503, 다른 오류(중첩 트랜잭션 미지원 포함)는 바꾸지 않음
- `RedisRefreshTokenStoreTest`: 기한이 지난 회전은 아무것도 바꾸지 않는다. 기한 검사를 빼는 변이를 잡는다

### 남은 위험

- Redis 장애 동안 새 연결과 재접속은 되지 않는다. 노드 재시작과 Redis 장애가 겹치면 그동안 아무도 편집하지 못한다. 막힘을 고른 대가다
- 생존 검사를 묶은 health 로 잘못 설정하면 Redis 장애가 노드 재시작으로 번진다. 배포 설정에서 `/actuator/health/liveness` 를 써야 한다(D-25)

## 4. SEC-3 presence 빈도 제한

- 문제: change 는 초당 30개로 막았지만 presence 는 막지 않았다(진단 시점 `CollabWebSocketHandler.java:123-131`). presence 하나가 같은 문서의 다른 연결(최대 99개)로 퍼지므로, 한 연결이 다른 모든 연결의 보낼 대기열을 채울 수 있었다
- 고친 것(#21, D-07): 연결마다 최근 1초에 `copage.collab.max-presence-per-second`(기본 30)를 넘는 presence 는 버린다(`CollabWebSocketHandler.java:164`). 연결은 끊지 않는다. 정상 클라이언트는 50ms 에 하나(초당 20개)라 걸리지 않는다
- 시험: `CollabWebSocketHandlerTest` — 한도를 넘는 presence 는 다른 연결로 가지 않고 보낸 연결은 끊기지 않는다, 1초 뒤 다시 받는다, change 와 따로 센다
- 남은 위험: 몰아 보낸 것의 마지막 커서가 버려지면 다음 presence 까지 낡은 커서가 남는다. 포커스를 잃어 커서를 지우는 presence 도 버려질 수 있다. 한도를 넘는 것은 정상 클라이언트가 아니라는 전제로 받아들였다

## 5. SEC-4~SEC-7 인증 보강 (#45, D-28)

| ID | 문제 | 결정과 고친 것 | 시험 | 남은 위험 |
| --- | --- | --- | --- | --- |
| SEC-4 | 재발급과 로그아웃(쿠키로 인증하는 경로)의 다른 출처 차단을 CORS 처리기에 기댔다. CORS 설정이 바뀌면 조용히 풀린다 | 컨트롤러가 `OriginCheck` 로 직접 본다. Origin 이 없거나, 같은 출처(scheme + Host)거나, `copage.web.allowed-origins` 에 있을 때만 받는다. 버린 안: CSRF 토큰(SameSite=Strict 와 Origin 검사로 충분하고 화면 처리가 는다) | `OriginCheckTest`: 없음, 같은 출처, 허용 목록은 받고, 다른 출처, scheme 다름, 접미사 속임, `null` Origin 은 막는다 | Origin 이 없는 요청은 받는다(브라우저가 아니거나 같은 출처). 브라우저가 아닌 곳에서 쿠키를 가진 공격자는 이 검사의 대상이 아니다 |
| 프록시 뒤 | 운영은 Caddy 가 TLS 를 끝내고 http 로 넘긴다. 같은 출처 판정이 http 를 보면 https 화면의 요청을 막는다 | `server.forward-headers-strategy: native`(`application.yml`). Tomcat 은 내부 주소(127/8, 10/8, 192.168/16 등)에서 온 요청의 X-Forwarded-* 만 믿는다 | 확인하지 않았다(프록시를 둔 배포가 아직 없다) | 프록시를 내부망 밖에 두면 헤더를 믿지 않아 같은 출처 판정이 틀린다. 프록시는 같은 기기나 내부망에 둔다 |
| SEC-5 | Access 토큰에 iss, aud 가 없었다. 같은 비밀키를 다른 서비스와 나누면 토큰이 섞인다 | iss(`copage.auth.issuer`, 기본 copage), aud(`copage.auth.audience`, 기본 copage-api)를 넣고 `JwtIssuerValidator` 와 aud 검증기로 확인한다(`SecurityConfig.java:58-61`). 알고리즘은 HS256 고정 | `AuthFlowTest`: 서명은 맞지만 iss/aud 가 없거나 다른 토큰은 401 | 배포 직후 예전 Access 토큰(최대 15분)이 401 이 된다. 화면은 Refresh 쿠키로 바로 다시 받는다 |
| SEC-6 | 가입이 이미 가입한 이메일에 409 를 준다(로그인은 숨긴다) | 유지한다. 가입 화면이 "이미 가입했다" 를 알려 주는 편이 사용자에게 낫다 | 바꾼 것이 없다 | 이메일 존재 확인을 대량으로 할 수 있다. 공개 배포(로드맵 7단계) 때 프록시의 IP 빈도 제한으로 막는다. 아직 없다 |
| SEC-7 | WebSocket 티켓이 주소(`?ticket=`)에 실린다 | 유지한다. 30초 1회용이고 지금은 접근 로그를 켜지 않아 남는 곳이 없다. 버린 안: 첫 메시지로 티켓 보내기(핸드셰이크 인증 구조를 바꿔야 한다) | 바꾼 것이 없다 | 프록시나 Tomcat 접근 로그를 켤 때 `ticket` 값을 가려야 한다. 배포 문서에 적어야 한다 |

## 6. CI 의 보안 검사 (OPS-2, #53, D-32)

- gitleaks(판 고정)로 커밋 이력 전체의 비밀키를 본다. 도입 때 결과: 커밋 72개, 새어 나간 비밀키 없음
- 화면 의존성의 high 이상 취약점(`npm audit --audit-level=high`). 도입 때 0건
- Dependabot(gradle, npm, actions, 주마다 develop). 처리 규칙은 D-35
- 하지 않은 것: Java 의존성 취약점 검사(Dependabot 의 갱신 PR 만 있다), 정적 분석. 정적 분석과 형식 검사는 코드 전체를 바꾸므로 사용자가 나중에 몰아서 할 리팩터링 때 정한다(D-32)

## 7. 그 밖에 이 작업에서 정한 보안 관련 동작

| 항목 | 내용 | 근거 |
| --- | --- | --- |
| 관리 포트 | 지표와 health 는 관리 포트(기본 8081, 127.0.0.1)에서만. API 포트의 `/actuator` 는 막는다. 관리 포트 요청만 여는 보안 체인을 따로 둔다 | D-25, `MetricsTest`(API 포트의 `/actuator/prometheus` 차단, 출력에 문서 ID 와 이메일 없음) |
| 블록 속성 검사 | 허용 키 넷(type, level, checked, language)과 값 규칙. language 는 32자 이하, 글자와 숫자와 `+ # . _ -` | D-29, `ChangePolicyTest` |
| 순서 키 검사 | 모양이 틀리거나 256 글자를 넘으면 거부. 다른 클라이언트의 키 만들기가 실패하지 않게 한다 | D-24, `ChangePolicyTest` |
| 순번 규칙 | 한 연결의 clientSeq 가 늘지 않으면 그 연결만 4008. 클라이언트 하나가 유일 제약 위반으로 문서의 모든 연결을 끊을 수 있던 길을 막는다 | D-38, `DocumentSessionTest` |
| 메시지 크기 | 컨테이너 글 버퍼를 한도(65,536 글자)와 같게. 글자 수까지 넘는 메시지는 1009 로 끊기고 클라이언트는 다시 붙지 않는다 | D-36 |

## 8. 남은 보안 일

우선순위와 크기는 [IMPROVEMENT-ROADMAP.md](IMPROVEMENT-ROADMAP.md) 에 있다.

- 다중 노드 권한 변경 전파(DC-1 과 함께)
- 공유 링크(S-4)와 초대 메일(W-1). 지금의 이메일로 바로 넣는 경로를 대체한다
- 가입과 로그인의 IP 빈도 제한(프록시), 접근 로그의 `ticket` 가리기(배포 준비)
- 속성 값 길이의 서버 검사. 지금은 화면이 짧은 값만 만들어 생기지 않고, 생기면 메시지 한도(4008)에 걸린다
