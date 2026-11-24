# 진단 개선 구현 보고

아키텍처 진단(2026-11-23)을 처리한 1~4단계의 작업을 PR 단위로 정리한다. 무엇을 바꿨고 어떤 시험으로 지키는지, API 와 규약이 어떻게 바뀌었는지, 하지 않은 것은 무엇이고 왜인지를 적는다.

판정과 근거는 [ARCHITECTURE-REVIEW.md](ARCHITECTURE-REVIEW.md), 결정은 [DECISIONS.md](DECISIONS.md) 에 있다.

## 1. 단계 요약

| 단계 | 한 일 | PR |
| --- | --- | --- |
| 1. 진단 | 피드백 항목을 코드, 시험, 측정으로 확인했다. 코드를 바꾸지 않았다. 기준 코드는 PR #17(프레즌스) 머지 직전의 `a538269` | 없음 |
| 2. 계획 | P0 수정 계획, 사용자가 고른 결정 넷(D-02~D-05) | 없음 |
| 3. 수정 | P0 9개, P1 4개, P2 5개 | #19~#53 |
| 4. 장애와 부하 시험 | 실험 도구, 실험, 실험이 찾은 결함 수정 | #68~#79 |
| 의존성 | Dependabot PR 처리(D-35) | #55~#60, #62, #63, #66, #71, #77 |

작업 방식(D-01): 문제마다 develop 에서 브랜치를 만들고, PR 에 줄 단위 리뷰 댓글을 단 뒤 반영 커밋을 올리고, `./gradlew build`, `npm run build`, `npm test`, 필요하면 Playwright e2e 가 통과하면 머지했다. 시험이 실제로 결함을 잡는지는 고친 줄을 되돌리거나 빼는 변이로 확인했다. 아래 "변이 확인" 은 그 결과다.

## 2. 3단계 P0

| PR | 제목 | 바꾼 것 | 시험 |
| --- | --- | --- | --- |
| #19 | 세션의 확정 기록을 최근 창으로 제한하고 오래된 baseRev 를 거부 (DC-2) | `ServerDocument` 기록 창(기본 5,000, `copage.collab.history-window`). 창 밖 baseRev 는 `Stale` 이고 4011. 창 밖 순번 재전송은 저장소로 거른다 | `SyncScenarioTest`, `DocumentSessionTest` |
| #21 | presence 에 연결별 빈도 제한 (SEC-3) | 연결마다 초당 30개(`copage.collab.max-presence-per-second`), 넘는 것은 버리고 끊지 않는다 | `CollabWebSocketHandlerTest`(새) |
| #23 | Redis 에 닿지 못하면 503, 열린 편집 연결은 그대로 (SEC-2) | `StoreUnavailableFilter`, Redis 시간 한도 2초, DB 연결 풀 대기 3초, 화면이 503 을 로그아웃으로 보지 않음, Refresh 회전 스크립트에 기한(D-08) | `RedisOutageTest`(고치기 전 500 으로 실패 확인), `StoreUnavailableFilterTest`, `RedisRefreshTokenStoreTest`, `http.test.ts` |
| #25 | 워크스페이스, 멤버, 페이지 트리 (SEC-1-1) | V3 마이그레이션, 워크스페이스와 페이지 API. 기존 문서를 첫 작성자의 "가져온 문서" 워크스페이스로 옮긴다(D-09) | `V3MigrationTest`, `JdbcWorkspaceRepositoryTest`, `WorkspaceFlowTest` |
| #29 | 페이지 권한 계산과 티켓 검사 (SEC-1-2) | V4 `page_grants`, permission 모듈(재귀 CTE + `PermissionRules`), 티켓 404/403, `GET /api/pages/{id}` | `PermissionRulesTest`, `PagePermissionsTest`, `PermissionFlowTest` |
| #31 | 권한 부여, 회수, restricted, 멤버 역할 변경과 내보내기 (SEC-1-3a) | 공유 API, 멤버 관리, `PermissionChangeListener` 포트. DECISIONS.md 를 만들었다 | `GrantFlowTest`, `JdbcWorkspaceRepositoryTest`(잠금을 결정적으로 확인, 잠금을 빼는 변이에서 실패) |
| #33 | 권한이 바뀌면 열린 연결에 1초 안에 적용하고 화면은 등급을 지킨다 (SEC-1-3b) | collab 이 리스너를 구현, `role` 메시지와 4003. 화면은 EDIT 미만이면 읽기 전용(D-18) | `DocumentSessionTest`, `ProtocolCodecTest`, `LiveRevocationTest`(리스너를 끄는 변이에서 실패), `role.test.ts`, e2e |
| #34 | 페이지 제목을 루트 블록에서 채운다 | 커밋 뒤 `TitleSink` 로 알리고 V5 `title_rev` 로 더 큰 리비전만 쓴다(D-21) | `DocumentSessionTest`, `PageTitleTest`, `JdbcWorkspaceRepositoryTest`, `TitleNormalizationTest`. 알림을 빼는 변이에서 두 시험이 실패 |
| #36 | 워크스페이스와 페이지 트리 화면, 공유 창, 멤버 화면 (SEC-1-4) | 편집기 경로 `/w/:workspaceId/p/:pageId`, 예전 `/p/:id` 는 옮김(D-22). 사이드바, 공유 창, 멤버 화면 | Playwright e2e 20개(새 4개), `GrantFlowTest` |

## 3. 3단계 P1, P2

| PR | 제목 | 바꾼 것 | 시험 |
| --- | --- | --- | --- |
| #37 | 순서 키가 로그로만 자라게 하고 서버가 키를 검사한다 (DC-5, P1) | 맨 뒤와 맨 앞은 첫 글자 아래에서 센다(D-24). 서버는 모양과 길이 256 검사. Java, TS 함께, 적합성 벡터 3,600건 추가 | `FractionalIndexTest`, 적합성(TS 의 세는 길이를 바꾸는 변이에서 300건 어긋남), `ChangePolicyTest` |
| #39 | 협업 지표를 관리 포트에 Prometheus 형식으로 (OPS-1, P1) | Micrometer + Prometheus, 관리 포트 8081/127.0.0.1, 지표 일곱 가지(D-25) | `MetricsTest`(출력에 문서 ID 와 이메일 없음, API 포트 차단), `DocumentSessionTest` |
| #41 | 원격 편집 하나 반영을 5,000 블록에서 301ms 에서 3ms 로 (PERF-1, P1) | 부모별 자식 색인, 같은 노드 건너뛰기(D-26). `npm run perf` | `document.test.ts`(색인이 전체를 훑은 결과와 같음, 정렬과 복사를 빼는 변이에서 실패) |
| #43 | 거부된 묶음에서 다른 블록의 편집은 살리고 못 살린 글은 보관함에 (DC-3, P1) | 블록 단위 살리기, 보관함, nack 에 `code`(D-27) | `salvage.test.ts`, e2e(살리기를 끄는 변이에서 실패), `ProtocolCodecTest` |
| #45 | 재발급과 로그아웃의 Origin 을 직접 검사하고 토큰에 iss, aud (SEC-4~7, P2) | `OriginCheck`, iss/aud 검증, `forward-headers-strategy: native`. SEC-6, 7 은 정책(D-28) | `OriginCheckTest`, `AuthFlowTest` |
| #47 | 블록 속성의 키와 값을 검사하고 구분선에 글을 넣지 못하게 (DC-6, P2) | 허용 키 넷과 값 규칙, 구분선 글 금지(D-29) | `ChangePolicyTest` |
| #49 | 확인되지 않은 내 편집이 있으면 커서를 미뤘다가 보낸다 | #47 의 e2e 에서 드러난 경쟁 조건. 상대 화면의 내 커서가 한 칸 뒤에 보였다(D-30) | presence e2e 2개를 15번씩 30번 통과(고치기 전 8번에 1번 실패) |
| #51 | 무작위 생성기에 이모지와 긴 글, 스냅샷과 로그 재생의 일치 (DC-7, P2) | `RandomOps` 확장, test fixtures 로 옮김, `SnapshotReplayPropertyTest`(D-31) | 새 시험(스냅샷 뒤 첫 연산을 빼는 변이에서 실패), 다시 만든 적합성 벡터 |
| #53 | 모듈 경계를 ArchUnit 으로, CI 에 비밀키와 의존성 검사 (ARCH-1, OPS-2, P2) | `ModuleBoundaryTest`, gitleaks, `npm audit`, Dependabot(D-32) | 금지 참조를 넣은 임시 클래스로 넷 중 셋이 실패하는 것 확인 |

## 4. 4단계 장애와 부하 시험

| PR | 제목 | 바꾼 것 | 시험, 측정 |
| --- | --- | --- | --- |
| #68 | DB 가 답하지 않아도 문서 세션이 멈추지 않고 클라이언트가 다시 맞추게 | JDBC 소켓 시간 한도 10초, 묶음 처리 중 저장소 실패도 세션 버리기(4009), Flyway 따로 연결(D-33). docs/CHAOS.md 를 만들었다 | `PostgresOutageTest`(고치기 전 "닫히지 않았다" 로 실패), `DocumentSessionTest`(다시 던지는 변이에서 실패) |
| #69 | 묶음을 50ms 에 하나만 보내 빠르게 쳐도 서버 한도에 걸려 끊기지 않게 | 클라이언트 전송 간격 50ms(D-34). 서버가 4008 로 닫을 때 사유를 WARNING 으로. CI 의 간헐 실패 e2e(#54)의 원인 | 새 e2e(초당 50타 150자, 1초 창 21개 이하, 간격 0 변이에서 실패) |
| #70 | TypeScript 7, @types/node 주 판, Jackson 단독 갱신은 Dependabot 이 열지 않게 | 의존성 갱신 규칙(D-35) | CI |
| #72 | 부하 시나리오 도구 | `loadgen` 모듈: one-doc, many-docs, open-doc | 첫 측정([PERFORMANCE-REVIEW.md](PERFORMANCE-REVIEW.md)) |
| #73 | WebSocket 글 버퍼를 메시지 한도와 같게 (연결 2,000개에서 OOM) | 컨테이너 글 버퍼 65,536 글자, 클라이언트가 1009 를 치명으로(D-36) | `CollabWebSocketIntegrationTest`, `connection.test.ts`(1009 를 빼는 변이에서 실패), loadgen 500 x 4 두 번 |
| #74 | 64KB 를 넘는 편집은 여러 묶음으로 나눠 보낸다 | `ClientSession.poll` 이 60,000 바이트 안으로, 큰 연산은 나눔(D-37) | `sendBudget.test.ts` 9개, e2e(한글 5만 자 붙여넣기). 나누기를 되돌리는 변이에서 실패 |
| #75 | 노드 강제 종료와 재접속 몰림 실험 | loadgen `reconnect=true`, 재개 시간 측정. `gradlew.bat` 줄 끝 정규화 | 연결 20/300/1,000 실험, ack 받은 묶음 유실 0 |
| #76 | 느린 클라이언트와 DB 지연 장애 실험 | `SlowClientTest`, `DbLatencyTest`(Toxiproxy). 느린 연결을 버릴 때 WARN | 전송을 세션 스레드에서 직접 하게 바꾸는 변이에서 `SlowClientTest` 실패 |
| #77 | Jackson 판을 Spring Boot 4.2.0 에 맞춘다 | ot-core 벡터 내보내기와 loadgen 의 Jackson 3.1.5 → 3.1.8 | `./gradlew :ot-core:test :loadgen:build` |
| #78 | 커밋 경로의 DB 왕복을 묶음 수와 상관없이 한 번으로 (PERF-2) | 중복 확인은 연결의 첫 묶음에서만, INSERT 문 하나, 한 연결의 clientSeq 는 늘기만(D-38). 리뷰에서 매개변수 한도(65,535)를 짚어 1,000 행씩 나누고, 나뉘면 한 트랜잭션 안에서 넣는다 | `DocumentSessionTest` 37개, `JdbcDocumentStoreTest`(섞인 묶음은 하나도 쓰지 않음, 10,000 행 묶음은 나누기를 빼면 실패), DB 지연 30.4초 → 7.0초 |
| #79 | 경로는 사이드바의 최신 제목을 보인다 (간헐 실패 e2e) | 페이지 경로가 예전 제목을 보이던 결함. 이 탭에서 고친 제목을 우선하고 나머지는 서버 제목을 쓴다. 고친 제목은 워크스페이스를 나갈 때 잊는다. IME e2e 의 간헐 실패는 재현되지 않았다(로컬 15번, 최근 CI 100번) | e2e 가 경로 응답의 제목을 일부러 비워 그 경우를 늘 만든다. 고치기 전 코드에서 CI 와 같게 실패 |

## 5. 의존성 갱신

Dependabot 이 연 PR 은 CI(빌드, 적합성, e2e)가 통과하면 머지했다(D-35).

| PR | 내용 |
| --- | --- |
| #55, #56, #57, #59, #60 | GitHub Actions(upload-artifact 7, setup-node 7, setup-java 6, checkout 7, gradle/actions 6) |
| #58 | JUnit 5.13.4 → 6.1.3 |
| #62 | Gradle wrapper 9.1.0 → 9.9.2 |
| #63 | ArchUnit 1.4.1 → 1.5.1 |
| #66 | Spring Boot 4.1.1 → 4.2.0 |
| #71 | TypeScript 5.9.3 → 6.0.3 |

받지 않은 것: TypeScript 7(#61, vue-tsc 가 쓰는 JS API 가 없어 CI 타입 검사가 실패), @types/node 주 판(#64, Node 22 에 맞춘다), Jackson 단독(#65, Boot 가 관리하는 판을 따른다). 설정의 ignore 로 다시 열리지 않게 했다(#70).

## 6. API 와 규약 변경

클라이언트나 운영 설정에 닿는 변경을 모았다. 05-PROTOCOL, 09-API 는 각 PR 에서 함께 고쳤다.

### 6.1 WebSocket 규약

| 변경 | 종류 | PR | 영향 |
| --- | --- | --- | --- |
| 기록 창보다 오래된 baseRev 의 묶음은 4011 로 닫는다(예전에는 받아서 기록 전체와 변환했다) | 기존 코드의 쓰임 추가 | #19 | 화면은 4011 이면 이미 재접속한다 |
| `role` 메시지를 보낸다. 권한을 잃으면 4003 으로 닫는다 | 규약에 있던 메시지 구현 | #33 | 화면은 role 에 따라 읽기 전용이 된다 |
| 순서 키의 모양이 틀리거나 256 글자를 넘으면 nack | 검사 추가 | #37 | 정상 클라이언트는 만들지 않는다 |
| nack 에 `code`(permission, input, empty, rejected) | 필드 추가 | #43 | 예전 클라이언트는 무시한다. code 가 없는 nack 는 새 클라이언트가 다시 보내지 않는다 |
| 블록 속성 키와 값 검사, 구분선 글 금지 | 검사 추가 | #47 | 어기면 nack(input). 이미 저장된 문서는 다시 검사하지 않는다 |
| 서버의 4008 은 그대로. 클라이언트가 초당 20개로 스스로 늦춘다 | 클라이언트 동작 | #69 | 규약 변경 없음 |
| 글자 수가 65,536 을 넘는 메시지는 컨테이너가 1009 로 끊는다. 클라이언트는 1009 를 치명으로 보고 다시 붙지 않는다 | 종료 코드 추가 | #73 | 정상 클라이언트는 이 크기를 보내지 않는다(#74 로 나눠 보낸다) |
| 64KB 를 넘는 편집을 클라이언트가 나눈다 | 규약에 적힌 동작 구현 | #74 | 규약 변경 없음 |
| 한 연결에서 clientSeq 가 늘지 않으면 4008. 연결의 첫 묶음이 확정되지 않은 순번인데 더 큰 순번이 저장되어 있어도 4008 | **규약 강화** | #78 | 같은 연결로 예전 순번을 다시 보내던 클라이언트는 끊긴다. 브라우저와 Java `ClientSession` 은 이미 늘리기만 한다 |

치명 종료 코드(다시 붙지 않음)는 지금 1009, 4003, 4008, 4010 이다(`frontend/src/collab/transport/connection.ts:26`).

### 6.2 HTTP API

| 변경 | PR | 영향 |
| --- | --- | --- |
| Redis 나 DB 에 닿지 못하면 500 대신 503 + `Retry-After: 5` | #23 | 화면은 503 을 로그아웃으로 보지 않는다 |
| 워크스페이스, 멤버, 페이지 API 추가 | #25 | 추가만 |
| 티켓 발급이 페이지 권한을 본다. 페이지가 없는 문서 ID 는 404, 등급이 없으면 403. 티켓의 role 이 계산한 등급(만든 사람은 FULL) | #29 | **동작 변경.** "아무 UUID 로 새 문서" 가 없어졌다. 페이지를 먼저 만든다 |
| 페이지 공유, restricted, 멤버 관리 API | #31 | 추가만 |
| `pages.title` 이 채워진다 | #34 | 응답 모양은 그대로 |
| 응답에 `restricted` 추가, 화면 경로 `/w/:workspaceId/p/:pageId` | #36 | 예전 `/p/:id` 는 옮겨 준다 |
| 재발급과 로그아웃의 Origin 직접 검사 | #45 | 다른 출처는 원래도 CORS 가 막았다. 동작 변화 없음 |
| Access 토큰에 iss, aud. 없거나 다르면 401 | #45 | 배포 직후 예전 토큰(최대 15분)은 401 이고 화면이 Refresh 로 바로 다시 받는다 |

### 6.3 데이터베이스 마이그레이션

| 판 | 내용 | 기존 데이터 |
| --- | --- | --- |
| V3 | `workspaces`, `workspace_members`, `pages`(`pos` 는 `COLLATE "C"`) | 지우지 않고 옮긴다. 기존 문서는 첫 연산 작성자의 "가져온 문서" 워크스페이스로. 작성자가 `users` 에 없는 문서는 옮기지 않고 연산 로그와 스냅샷을 그대로 둔다(그 문서는 열 수 없다, D-09). `V3MigrationTest` |
| V4 | `page_grants` | 테이블 추가만 |
| V5 | `pages.title_rev`(기본 0) | 열 추가만. 제목은 처음 열 때 채워진다 |

### 6.4 운영 설정

| 설정 | 기본 | PR |
| --- | --- | --- |
| `copage.collab.history-window` | 5000 | #19 |
| `copage.collab.max-presence-per-second` | 30 | #21 |
| `COPAGE_REDIS_TIMEOUT`, `COPAGE_REDIS_CONNECT_TIMEOUT` | 2s | #23 |
| `COPAGE_DB_CONNECT_TIMEOUT_MS` | 3000 | #23 |
| `COPAGE_MANAGEMENT_PORT`, `COPAGE_MANAGEMENT_ADDRESS` | 8081, 127.0.0.1 | #39 |
| `copage.auth.issuer`, `copage.auth.audience` | copage, copage-api | #45 |
| `server.forward-headers-strategy` | native | #45 |
| `COPAGE_DB_SOCKET_TIMEOUT_SECONDS` | 10 | #68 |

생존 검사는 `/actuator/health/liveness` 를 써야 한다. 묶은 `/actuator/health` 를 쓰면 Redis 장애가 노드 재시작으로 번진다(D-25).

## 7. 하지 않은 것

| 항목 | 이유 | 어디서 |
| --- | --- | --- |
| DC-1 다중 노드 소유권(리스, 라우팅, 양도), 권한 변경의 노드 간 전파 | 로드맵 4단계의 범위다. 노드 1대에서는 문제가 생기지 않는다. 쓰기 정합성은 DB 기본 키가 이미 지킨다 | IMPROVEMENT-ROADMAP 1번 |
| 펜싱 토큰 | 필요 없다고 판단했다(D-04) | ARCHITECTURE-REVIEW 3.1 |
| Kafka | 이벤트를 소비할 기능이 없다 | ARCHITECTURE-REVIEW 3.1 |
| 권한 캐시 | 회수가 늦게 적용될 위험. 부하 측정에서 병목이 아니었다(D-11) | |
| DC-4 오프라인 저장, DC-8 로그와 삭제 블록 정리 | 로드맵 4, 5단계 기능과 함께 설계한다 | IMPROVEMENT-ROADMAP |
| rebase:false 의 복사본 페이지 | 살리기와 보관함으로 입력 유실을 먼저 막았다(D-27) | |
| 블록 종류별 허용 속성 | 이미 남은 속성이 있는 문서의 편집이 거부된다(D-29) | |
| 속성 값의 서버 길이 한도 | 화면이 짧은 값만 만든다. 지금은 메시지 한도가 상한이다 | IMPROVEMENT-ROADMAP |
| WebSocket 부분 메시지 조립 | 크기 확인과 조립을 직접 짜야 한다. 연결 수 목표가 늘 때 다시 본다(D-36) | IMPROVEMENT-ROADMAP |
| 사이드바의 남의 변경 실시간 반영(W-5, SSE) | 별도 작업(D-23) | IMPROVEMENT-ROADMAP |
| 진단 PERF-2 의 "묶음마다 블록 맵 복사" | 재 보니 지연의 주된 원인은 DB 왕복이었다. 블록 맵 복사는 재지 않았다 | |
| 형식 검사와 정적 분석 | 도구를 들이면 코드 전체가 바뀐다. 사용자가 나중에 할 리팩터링 때 정한다(D-32) | |
| 1.4 의 시뮬레이션 100만 회, 4GB 서버 측정 | 노트북 한 대로 쟀다 | PERFORMANCE-REVIEW 4장 |
| 공유 링크, 초대 메일 | 로드맵 3단계의 남은 부분. 메일 발송 인프라가 필요하다(D-22) | IMPROVEMENT-ROADMAP |
| 펜싱 토큰 판단의 ADR | D-04 는 ADR 로 남기기로 했다. 기준 코드(`7e1e39c`)의 `docs/adr/` 에는 아직 없다 | |
