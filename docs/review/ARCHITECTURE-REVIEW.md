# 아키텍처 진단 결과

2026-11-23 에 받은 시니어 백엔드 아키텍처 진단(이하 피드백)을 코드로 확인하고 처리한 결과다. 피드백은 설계 문서만 보고 작성되었다. 그래서 항목마다 실제 코드에서 문제가 있는지부터 확인했다.

- 같은 묶음의 다른 보고서: [보안](SECURITY-REVIEW.md), [데이터 정합성](DATA-CONSISTENCY-REVIEW.md), [성능](PERFORMANCE-REVIEW.md), [구현 보고](IMPLEMENTATION-REPORT.md), [남은 일](IMPROVEMENT-ROADMAP.md), [결정 기록](DECISIONS.md)
- 결정 번호 D-xx 는 [DECISIONS.md](DECISIONS.md), PR 번호 #NN 은 GitHub 의 PR 이다

## 1. 진행 순서

| 단계 | 한 일 | 결과물 |
| --- | --- | --- |
| 1. 진단 | 피드백 항목마다 코드와 시험을 확인하고, 성능 항목은 직접 쟀다. 코드는 바꾸지 않았다 | 실제 문제 목록(SEC, DC, PERF, OPS, ARCH)과 문제가 아닌 항목 |
| 2. 계획 | P0 네 건과 설계를 먼저 정할 셋(권한, 펜싱 토큰, 순서 키)의 안을 비교하고 사용자가 골랐다 | D-02~D-05 |
| 3. 수정 | 문제마다 브랜치와 PR. P0, P1, P2 순서 | #19~#53 |
| 4. 장애와 부하 시험 | 장애 실험, 부하 도구, 실험이 찾은 결함 수정 | #68~#79, [docs/CHAOS.md](../CHAOS.md) |
| 5. 정리 | 결정 기록, 보고서, 문서 갱신 | 이 문서들 |

진단의 기준 코드는 `feature/08-presence` 의 `a538269`(PR #17)였다. 아래 "진단 시점" 의 파일 위치는 그 코드 기준이고, "지금" 은 develop 의 `7e1e39c`(PR #79 머지) 기준이다.

## 2. 확인 방법

- 코드: 피드백이 지적한 동작이 실제 코드 경로에 있는지 읽었다. 위치를 `파일:줄` 로 적었다
- 시험: 이미 있는 시험이 그 동작을 고정하는지 봤다. 고정한다고 판단한 경우는 시험에서 핵심 줄을 빼는 변이로 시험이 실패하는지 확인했다(예: ack 를 커밋 전에 보내는 변이)
- 측정: 인덱스(PostgreSQL 17, 연산 20만 건), 순서 키 길이(Node 22), 클라이언트 반영 시간(Node 22)은 직접 쟀다. 측정 기기는 Apple M1 Pro 노트북 한 대다
- 판정은 셋 중 하나로 했다. **실제 문제**(코드에 있고 재현되거나 측정됨), **문제 아님**(코드가 이미 막거나 측정으로 문제가 없음), **아직 없음**(설계만 있고 기능이 없어 따질 대상이 없음)

## 3. 피드백 항목별 판정

### 3.1 문제가 아니라고 판정한 항목

| 피드백 항목 | 판정 | 근거 |
| --- | --- | --- |
| 펜싱 토큰을 도입해야 한다 | 문제 아님. 도입하지 않는다(D-04) | `doc_ops` 의 `PRIMARY KEY (doc_id, rev)` (`app/src/main/resources/db/migration/V1__doc_ops_and_snapshots.sql`)와 노드마다 연속인 리비전이 낙관적 동시성 검사 역할을 한다. 노드가 리비전 k 를 넣는 데 성공했다면, 그 노드는 k-1 을 불러왔거나 직접 넣었고, 그 사이 다른 노드가 k 를 넣었다면 충돌이 난다. 그래서 이전 소유자의 늦은 쓰기는 거부되거나, 성공했다면 올바른 사슬이다. 묶음 커밋은 INSERT 문 하나(1,000 행을 넘으면 한 트랜잭션)라 일부만 남지 않는다(`JdbcDocumentStore.java:106-133`). 남는 위험(다중 노드의 읽기)은 DC-1 로 따로 둔다 |
| 오래된 스냅샷이 최신을 덮어쓴다 | 문제 아님 | 스냅샷은 `(doc_id, rev)` 기본 키에 `ON CONFLICT DO NOTHING` 으로 넣고, 열 때 `ORDER BY rev DESC LIMIT 1` 로 가장 새것을 읽는다. 3개만 남긴다. `JdbcDocumentStoreTest` 가 고정한다. 스냅샷과 로그 재생의 일치는 DC-7 에서 무작위 시험으로 더했다(`SnapshotReplayPropertyTest`, #51) |
| 리비전 경쟁, 같은 change 두 번, ack 두 번 | 문제 아님 | 위 기본 키, `UNIQUE (doc_id, client_id, client_seq)`, 세션의 순번 확인, 클라이언트 `onAck` 의 순번 검사. 무작위 끊김을 섞는 `CollabWebSocketIntegrationTest` 와 `ConvergenceSimulationTest` 가 "한 번만 적용, 빠짐 없음" 을 본다 |
| ack 를 커밋 전에 보낸다 | 문제 아님 | `DocumentSession.drain()` 이 `store.append` 가 끝난 뒤에 답을 보낸다(지금 `DocumentSession.java:381-419`). `DocumentSessionTest` 의 "커밋 실패 시 ack 없음" 이 고정하고, ack 를 커밋 앞으로 옮기는 변이에서 실패한다 |
| 메시지 크기, 빈도, 느린 클라이언트 보호 | 구현되어 있었다 | 64KB, 초당 30 change, 문서당 100 연결, 보낼 대기열 1MB/10초, 무응답 45초(`CollabProperties.java:22-38`, `WebSocketConnection.java`). 4단계에서 느린 클라이언트를 실제 서버로 다시 확인했다(`SlowClientTest`, #76). presence 의 빈도 제한만 빠져 있었다(SEC-3) |
| CSRF | 진단 시점에도 막혀 있었다 | API 는 Authorization 헤더로 인증한다. 쿠키를 쓰는 곳은 Refresh 하나이고 `SameSite=Strict`, 경로 `/api/auth`. 다른 출처의 재발급은 CORS 처리기가 403(`AuthFlowTest`). 다만 CORS 설정에 암묵적으로 기대어 SEC-4 로 명시적 Origin 검사를 더했다(#45) |
| DB 인덱스가 부족하다 | 문제 아님(측정) | 연산 20만 건에서 `changesSince` 0.3ms(기본 키 비트맵), 순번 조회 0.12ms(유일 색인), 최신 스냅샷과 정리 0.05ms. 네 질의 모두 0.1~0.3ms 안이다. 시간 기준 분할을 하지 않는 근거는 ADR 0011 |
| Kafka 가 필요하다 | 필요 없다 | 이벤트를 소비할 기능(댓글, 검색, 알림)이 아직 없다. 생기면 PostgreSQL Outbox(연산과 같은 트랜잭션)로 충분하다는 판단이다. 이 판단은 그 기능을 만들 때 다시 본다 |
| 권한 캐시 일관성, 공유 링크 보안, Redis pub/sub 유실 | 아직 없음 | 진단 시점에는 기능 자체가 없었다. 권한은 SEC-1 에서 만들면서 캐시를 두지 않았다(D-11). 공유 링크와 pub/sub 은 아직 없다([남은 일](IMPROVEMENT-ROADMAP.md)) |

### 3.2 실제 문제로 판정한 항목

심각도는 진단 시점 구성(노드 1대, 개발 단계)을 기준으로 했다.

| ID | 심각도 | 문제 | 진단 시점 위치 | 판정 근거 | 처리 |
| --- | --- | --- | --- | --- | --- |
| SEC-1 | HIGH | 권한 검사가 없다. 로그인한 사용자면 아무 문서 ID 에나 EDIT 티켓을 받는다 | `TicketController.java:33` | `AuthFlowTest` 의 티켓 발급이 아무 UUID 로 성공했다 | 워크스페이스, 페이지, 권한 계산, 부여와 회수, 연결 중 회수, 화면(#25, #29, #31, #33, #34, #36) |
| SEC-2 | MEDIUM | Redis 장애 시 동작이 정해지지 않았다 | `AuthService.java:165-171`, `SecurityConfig.java:64` | Redis 를 일시 정지하니 `/api/me` 가 500, 요청이 60초 매달렸다(#23 에서 확인) | 막힘(fail-closed), 503(#23, D-03) |
| SEC-3 | MEDIUM | presence 빈도 제한이 없다 | `CollabWebSocketHandler.java:123-131` | change 만 `checkRate` 를 거쳤다 | 연결마다 초당 30개, 넘는 것은 버림(#21, D-07) |
| SEC-4~7 | LOW | Refresh 의 Origin 검사가 CORS 에 기댐, JWT 에 iss/aud 없음, 가입 409, 티켓이 주소에 실림 | `identity/` | 코드 확인 | #45, D-28 |
| DC-1 | MEDIUM(다중 노드) | 다중 노드 소유권이 없다 | 코드에 lease, owner, epoch 없음 | 노드 1대에서는 문제가 아니다 | **열려 있다**. 로드맵 4단계 |
| DC-2 | MEDIUM | 세션의 확정 기록과 순번 맵이 끝없이 쌓이고, 아주 오래된 baseRev 도 받아 기록 전체와 변환한다 | `ServerDocument.java:24-25, 69-75` | 코드 확인(메모리와 CPU 는 재지 않았다) | 기록 창 5,000, 창 밖은 4011(#19, D-06) |
| DC-3 | MEDIUM | nack 와 rebase:false 에서 대기 편집을 통째로 버린다 | `clientSession.ts:143-150`, `collabClient.ts:226` | 정해진 동작이었지만 사용자 입력 유실이다 | 블록 단위로 살리고 보관함(#43, D-27) |
| DC-4 | LOW | 오프라인 저장(IndexedDB)이 없다 | 코드 없음 | 설계만 있다 | 열려 있다 |
| DC-5 | MEDIUM | 순서 키가 맨 뒤 추가마다 길어진다 | `FractionalIndex.java`, `fractionalIndex.ts` | 맨 뒤 1,000번 167자(Node 22 측정) | 맨 뒤 1,000번 3자, 서버 길이 256 한도(#37, D-24) |
| DC-6 | LOW | 블록 속성을 검사하지 않는다 | `ChangePolicy.java` | 코드 확인 | 키와 값 검사, 구분선 글 금지(#47, D-29) |
| DC-7 | LOW | 무작위 생성기에 이모지와 긴 글이 없고, 스냅샷과 재생 일치의 무작위 시험이 없다 | `RandomOps` | 코드 확인 | #51, D-31 |
| DC-8 | LOW | 연산 로그 90일 정리, 삭제 블록 30일 정리가 없다 | 코드 없음 | 설계만 있다 | 열려 있다 |
| PERF-1 | MEDIUM | 클라이언트가 메시지마다 편집기 문서 전체를 다시 만들고 비용이 블록 수의 제곱에 가깝다 | `frontend/src/collab/ot/document.ts:111`, `adapter/model.ts` | 5,000 블록 `toPm` 248ms(Node 22 측정) | 5,000 블록 3.1ms(#41, D-26) |
| PERF-2 | LOW(진단), 측정 뒤 실제 문제 | 서버 커밋 경로 | `Document.java`, 커밋 경로 | 진단 때는 재지 않았다. 4단계 DB 지연 실험에서 커밋 하나가 묶음 N 개에 DB 왕복 2N+1 번임을 찾았다 | 왕복 한 번(#78, D-38) |
| OPS-1 | MEDIUM | 지표가 없다 | 의존성 없음 | 코드 확인 | Micrometer + Prometheus(#39, D-25) |
| OPS-2 | LOW | CI 에 비밀키, 의존성 취약점 검사가 없다 | `.github/workflows/ci.yml` | 코드 확인 | gitleaks, npm audit, Dependabot(#53, D-32) |
| ARCH-1 | LOW | 모듈 경계를 검사하지 않는다 | 02-TECH-STACK 에 Spring Modulith 를 적었지만 의존성 없음 | 경계는 깨끗했다(collab → identity 참조는 설정 하나) | ArchUnit `ModuleBoundaryTest`(#53, D-32) |

진단 뒤 4단계 시험이 더 찾은 결함은 [구현 보고](IMPLEMENTATION-REPORT.md) 4장에 있다(DB 소켓 시간 한도, 연결 2,000개 OOM, 64KB 넘는 붙여넣기, 클라이언트 전송 간격).

## 4. 지금의 구조

### 4.1 모듈

배포 단위는 하나(Spring Boot 4.2, Java 25)다. Gradle 모듈은 셋, 앱 안의 업무 모듈은 넷이다.

| 모듈 | 책임 | 의존 |
| --- | --- | --- |
| `ot-core` (Gradle) | 문서 모델, 연산 7종의 적용, 변환, 합성, 역연산, 순서 키, `ServerDocument`, `ClientSession` | 아무것도 참조하지 않는다. 스프링을 모른다 |
| `app` / `identity` | 가입, 로그인, JWT, Refresh 회전, 로그인 잠금 | Redis, PostgreSQL |
| `app` / `workspace` | 워크스페이스, 멤버, 페이지 트리, 제목 | permission 에 권한을 묻는다 |
| `app` / `permission` | 페이지 권한 계산, 부여와 회수, 변경 알림 포트 | 다른 모듈의 자바 코드를 참조하지 않고 테이블만 읽는다 |
| `app` / `collab` | 문서 세션, WebSocket 규약, 연산 로그와 스냅샷, 티켓, 프레즌스, 지표 | ot-core. permission 과 workspace 는 app 계층 포트로만 쓴다 |
| `app` / `web` | 저장소 장애를 503 으로 바꾸는 필터, CORS | |
| `frontend` | Vue 3 + Tiptap 편집기, 어댑터, TS 로 다시 짠 OT 코어와 클라이언트 상태 기계 | 적합성 벡터로 Java 와 같은 결과를 확인한다(ADR 0009) |
| `loadgen` (Gradle) | 부하와 장애 시나리오 도구. ot-core 의 `ClientSession` 을 그대로 쓴다 | app 의 `OpJson` 만 쓴다 |

모듈 안은 `domain`, `app`, `in`, `out`, `config` 로 나눈다. 규칙은 ArchUnit 시험 `ModuleBoundaryTest` 가 지킨다(D-32): 모듈 사이 순환 없음, 다른 모듈은 `app` 과 `domain` 으로만 쓴다, `domain` 과 `app` 은 `in`, `out`, `config` 를 모른다, `domain` 은 스프링을 모른다. `workspace.app` 에서 `collab.out` 을 쓰는 임시 클래스를 넣으면 넷 중 셋이 실패하는 것을 확인했다. 설계 문서(02-TECH-STACK)에 적었던 Spring Modulith 대신 ArchUnit 을 고른 이유는 D-32 에 있다.

설계(03-ARCHITECTURE 5.1)의 `history`, `comment`, `notification`, `search` 모듈은 아직 없다.

### 4.2 권한 모듈

- 계산: 재귀 CTE 하나(`JdbcPermissionRepository.java:32`)가 페이지부터 루트까지의 사슬(restricted, 휴지통 여부), 사슬 위의 내 부여, 내 워크스페이스 역할을 함께 읽는다. 규칙은 순수 함수 `PermissionRules` 가 적용한다(07-SECURITY 9.4)
- 캐시: 두지 않는다(D-11). 회수가 캐시 때문에 늦게 적용되는 문제를 만들지 않기 위해서다. 100 문서 x 3명 부하에서 Hikari 연결 획득 평균 0.04ms, 연결 점유 평균 0.9ms 였다([성능](PERFORMANCE-REVIEW.md)). 지금은 병목이 아니다
- 진입점: 티켓 발급(`TicketController.java:40`)이 등급을 계산해 티켓에 싣는다. 없는 것과 볼 수 없는 것은 같은 404, 속해 있지만 등급이 없으면 403(D-10)
- 변경 전파: 권한이 바뀌면 커밋 뒤 `PermissionChangeListener` 포트로 "영향받는 페이지와 사용자" 를 알린다(D-17). collab 이 열린 세션 가운데 그 사용자의 연결만 다시 계산해 `role` 메시지나 4003 을 보낸다. 다시 계산(DB 읽기)은 세션 실행 흐름 밖에서 한다(D-19). 노드 1대 기준이다

### 4.3 문서 세션과 커밋 경로

한 문서는 한 노드의 한 세션이 맡는다(ADR 0004). 세션은 직렬 실행기(`SerialExecutor`)로 메시지를 하나씩 처리하므로 편집 경로에 락이 없다.

1. 받은 change 를 세션 대기열에 넣는다
2. `drain()`(`DocumentSession.java:381`)이 쌓인 묶음을 한 차례에 처리한다. 묶음마다(`process`, `:422`)
   - 연결의 순번이 앞 순번보다 커야 한다. 아니면 그 연결만 4008(D-38)
   - 입력 검사(`ChangePolicy`): 등급, 블록 종류, 서식, 속성, 순서 키. 걸리면 nack 와 분류 `code`
   - 연결의 첫 묶음이면 저장소에 그 순번의 확정 여부와 가장 큰 순번을 한 번 묻는다(`:453`). 이미 확정되었으면 그 리비전으로 ack
   - `ServerDocument.submit` 이 기록 창 안에서 변환하고 적용한다. 창보다 오래된 baseRev 는 `Stale` 이고 4011(D-06)
3. 받아들인 묶음을 INSERT 문 하나로 커밋한다(`JdbcDocumentStore.java:106-133`, D-38). 1,000 행을 넘으면 1,000 행씩 나눠 한 트랜잭션 안에서 넣는다
4. 커밋이 끝난 뒤에만 다른 연결에 change 를 보내고 보낸 사람에게 ack 한다(ADR 0005)
5. 커밋이나 그 전의 저장소 읽기가 실패하면 세션을 버리고 모든 연결을 4009 로 닫는다(D-33). 클라이언트는 다시 붙어 같은 순번으로 다시 보낸다
6. 200 리비전마다, 그리고 세션을 내릴 때 스냅샷을 남긴다. 루트 블록 글이 바뀌었으면 `TitleSink` 로 페이지 제목을 알린다(D-21)

### 4.4 Redis 와 PostgreSQL 장애

- Redis 는 Access 토큰 차단 목록, Refresh 토큰, 로그인 잠금, 1회용 티켓을 든다. 편집 연결은 Redis 를 쓰지 않는다
- 정책은 막힘이다(D-03). 가장 바깥 필터 `StoreUnavailableFilter`(`web/StoreUnavailableFilter.java:42`)가 연결 실패와 응답 시간 초과만 503 + `Retry-After: 5` 로 바꾼다. 클라이언트는 503 을 로그아웃으로 보지 않는다
- 생존 검사는 `/actuator/health/liveness` 만 쓴다. 묶은 health 는 Redis 를 보므로 생존 검사에 쓰면 Redis 장애가 노드 재시작으로 번진다(D-25)
- PostgreSQL 이 답하지 않으면 소켓 시간 한도(기본 10초) 뒤 세션을 버린다(D-33). 실험 결과는 [docs/CHAOS.md](../CHAOS.md)

### 4.5 지표

관리 포트(기본 8081, 127.0.0.1)의 `/actuator/prometheus` 로 낸다. API 포트의 `/actuator` 는 막는다(D-25).

| 지표 | 이름 |
| --- | --- |
| ack 지연(받음부터 ack 까지, 커밋 대기 포함) | `collab.ack.latency` (히스토그램) |
| 묶음당 변환 횟수 | `collab.transform.count` |
| 거부 | `collab.nack{reason}` (permission, input, empty, rejected) |
| 커밋 묶음 크기 | `collab.commit.batch.size` |
| 열린 세션, 연결 | `collab.sessions`, `collab.connections` (게이지) |
| 세션 복구 시간 | `collab.session.load` |
| change 메시지 크기 | `collab.op.bytes` |

레이블에 문서 ID 와 사용자 ID 를 넣지 않는다. 13.4 의 리스 충돌 지표는 리스가 없어 아직 없다.

## 5. 구조를 정한 결정

| 결정 | 내용 |
| --- | --- |
| ADR 0001, 0005 | 서버가 순서를 정하는 OT, 커밋한 뒤 ack |
| ADR 0004 | 한 문서는 한 노드가 소유. 다중 노드 리스는 아직 없다 |
| ADR 0008 | 모듈식 단일 배포 |
| ADR 0009 | Java 와 TS 두 구현을 적합성 벡터로 맞춘다 |
| ADR 0011 | 연산 로그를 시간으로 나누지 않는다. 인덱스 측정이 뒷받침한다 |
| ADR 0012 | 구조 충돌은 nack. 클라이언트 쪽 살리기는 D-27 |
| D-02, D-09~D-17 | 권한 모델과 전파 |
| D-03 | Redis 장애 시 막힘 |
| D-04 | 펜싱 토큰 없이 DB 기본 키로 정합성 |
| D-11 | 권한 캐시 없음 |
| D-25 | 지표의 포트, 히스토그램, 레이블 |
| D-32 | ArchUnit 으로 모듈 경계 |
| D-33, D-36, D-38 | 4단계 시험이 바꾼 커밋 경로와 WebSocket 설정 |

D-04 는 "ADR 로도 남긴다" 고 적었다. 이 보고서의 기준 코드(develop `7e1e39c`)의 `docs/adr/` 에는 펜싱 토큰 ADR 이 없다. 근거는 D-04 와 이 문서 3.1 에 있다.

## 6. 남은 구조 위험

| 위험 | 지금 | 계획 |
| --- | --- | --- |
| 다중 노드에서 두 노드가 같은 문서 세션을 든다 | 노드 1대라 생기지 않는다. 쓰기는 DB 기본 키가 막지만, 쓰기가 없으면 오래된 노드가 낡은 welcome 과 remote 를 계속 줄 수 있다 | 리스(Redis)로 세션 소유를 정하고, 리스를 잃으면 세션을 내리고 4009(DC-1, [남은 일](IMPROVEMENT-ROADMAP.md)) |
| 권한 변경 알림이 다른 노드에 닿지 않는다 | 프로세스 안 포트(D-17) | 다중 노드와 함께 Redis pub/sub |
| 파생 쓰기(페이지 제목)가 연산 INSERT 와 다른 트랜잭션이다 | `title_rev` 로 더 큰 리비전만 쓴다(D-21). 노드 1대에서는 순서가 지켜진다 | 다중 노드에서 오래된 노드가 쓰지 못하게 하는 방법을 리스와 함께 정한다 |
| 연결당 WebSocket 버퍼 약 131KB | 2,000 연결에 263MB(D-36) | 부분 메시지 조립(연결 수 목표가 늘 때) |
