# CoPage

여러 사람이 한 페이지를 동시에 고쳐도 모두 같은 결과를 보게 되는 블록 기반 문서 서비스다. 동시 편집 충돌은 서버 주도 OT(Operational Transformation)를 Java로 직접 구현해서 푼다.

Java 25, Spring Boot 4.2, Spring Security 7.2, PostgreSQL 17, Redis 7, Vue 3, Tiptap 3을 쓴다.

## 지금 상태

1단계(OT 코어)와 2단계(협업 서버)를 마쳤고, 3단계(워크스페이스와 권한)의 대부분과 4, 6단계의 일부(노드 1대의 장애 실험과 부하 측정)를 했다. 지금은 노드 1대 기준이다. 전체 계획과 단계별 상태는 [docs/12-ROADMAP.md](docs/12-ROADMAP.md)에 있다.

만든 것:

- 블록 10종(문단, 제목 1~3, 글머리 기호, 번호, 할 일, 토글, 인용, 콜아웃, 코드, 구분선)과 글자 서식 6종, Tab 들여쓰기, 마크다운 단축 입력을 쓰는 편집기
- 여러 명이 한 페이지를 동시에 고치는 서버 주도 OT. Java 와 TypeScript 두 구현이 적합성 벡터 14,600건에서 같은 결과를 낸다
- 남의 커서와 접속자 목록
- 끊겼다 다시 붙을 때 보내지 못한 편집을 한 번만 확정(메모리에서. 브라우저를 닫으면 사라진다)
- 거부된 편집을 블록 단위로 살리고, 못 살린 글은 편집기 위 보관함에 보인다
- 가입, 로그인, JWT 와 Refresh 토큰 회전, 로그아웃
- 워크스페이스, 멤버(가입한 사용자를 이메일로 바로 넣기), 역할(OWNER, MEMBER, GUEST), 페이지 트리, 제목 줄에서 채우는 페이지 제목
- 페이지 권한(VIEW, COMMENT, EDIT, FULL), 상속과 restricted, 공유 창. 권한을 바꾸면 열린 편집기에 1초 안에 적용된다
- Redis 장애 때 503 으로 막고 열린 편집은 계속(fail-closed), 관리 포트의 Prometheus 지표, 부하 도구 `loadgen`

아직 없는 것: 실제 서버에 올린 배포(구성과 문서는 있다). 초대 메일은 메일 서버를 설정하면 보낸다(D-50).

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 0 | 검증 실험 (편집기 어댑터 시제품) | 편집기 어댑터 완료. pg_bigm 확인은 아직 |
| 1 | OT 코어 (Java, TypeScript, 적합성 벡터) | 완료 |
| 2 | 협업 서버 | 완료 |
| 3 | 워크스페이스와 권한 | 대부분. 초대 링크, 공유 링크, 옮기기와 사이드바 끌기, 휴지통과 30일 뒤 비우기, 사이드바 실시간 반영(D-46, D-47). 초대 메일과 페이지 깊이 제한은 없다 |
| 4 | 오프라인과 다중 노드 | 완료. 브라우저 보관(D-42), 소유 리스와 양도, 노드 사이 권한 전달, 프록시 해시(D-43), 노드 두세 대의 장애 실험([docs/CHAOS.md](docs/CHAOS.md)). 여러 노드로 띄우기: `deploy/cluster/compose.yaml` |
| 5 | 부가 기능 | 완료. 버전 기록과 복원(D-44), 되돌리기, 슬래시 메뉴, 블록 끌기(D-45), 댓글, 멘션과 알림(D-46), 검색과 프로필(D-47) |
| 6 | 측정과 개선 | 완료. 노트북의 측정값을 [docs/01-PLAN.md](docs/01-PLAN.md) 1.4 와 [PERFORMANCE-REVIEW](docs/review/PERFORMANCE-REVIEW.md) 에 적었다(검색, 연산 로그 크기, 스냅샷 간격, 노드 둘, 2코어 4GB 로 묶은 노드). 실제 서버에서는 배포 뒤에 다시 잰다 |
| 7 | 배포 | 준비 완료. 서버 한 대 운영 구성([deploy/prod](deploy/prod), [docs/DEPLOY.md](docs/DEPLOY.md))을 로컬에서 띄워 확인했다. 시연 영상은 [docs/demo/copage-demo.mp4](docs/demo/copage-demo.mp4). 실제 AWS 서버와 도메인은 직접 만든다 |

## 모듈

| 모듈 | 내용 |
| --- | --- |
| `ot-core` | 문서 모델, 연산 7종, 적용, 변환, 합성, 역연산, 서버와 클라이언트의 동기화 규칙. 외부 의존성이 없다 (테스트의 벡터 생성기만 Jackson을 쓴다). 무작위 연산 생성기는 test fixtures 로 `app` 도 쓴다 |
| `app` | 서버. 계정(`com.copage.identity`), 워크스페이스와 페이지 트리(`workspace`), 페이지 권한(`permission`), 협업(`collab`: 문서 세션, WebSocket 규약, 연산 로그와 스냅샷, 티켓, 지표) 모듈. 모듈 경계는 ArchUnit 으로 검사한다. 데이터는 PostgreSQL(Flyway V1~V5), 토큰과 티켓은 Redis |
| `loadgen` | 부하 도구. `ot-core` 의 클라이언트 상태 기계를 그대로 쓰는 가상 편집자를 실제 서버에 붙인다 |
| `frontend` | 프론트엔드 (Vue 3, Vite, Pinia, Tiptap 3). 로그인과 가입, 워크스페이스와 페이지 트리, 편집기, 공유 창, 멤버 화면. `src/collab/` 아래에 OT 코어(`ot`), 상태 기계와 협업 클라이언트(`client`), WebSocket 연결(`transport`), 편집기 어댑터(`adapter`)가 있다 |

## 빠르게 띄워 보기

JDK 25, Docker, Node.js 22.12 이상이 필요하다.

```bash
docker compose up -d                                   # PostgreSQL 17(5432), Redis 7(6379)
./gradlew :app:bootRun --args='--spring.profiles.active=dev'   # API 와 WebSocket 8080, 관리 포트 127.0.0.1:8081

# 다른 터미널에서
cd frontend
npm ci
npm run dev
# http://localhost:5173 에서 가입한다. 워크스페이스를 만들고 사이드바에서 페이지를 만든다.
# 다른 브라우저(다른 사용자)로 가입한 뒤, 첫 사용자가 멤버 화면에서 넣거나 공유 창에서 권한을 주면 같이 편집된다.
```

| 환경 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `COPAGE_DB_PORT`, `COPAGE_REDIS_PORT` | 5432, 6379 | `compose.yaml` 과 서버가 함께 쓴다. 포트를 다른 것이 쓰고 있으면 두 명령 모두에 같은 값을 준다 |
| `COPAGE_JWT_SECRET` | dev 프로필만 개발용 키 | 32바이트 이상. dev 가 아닌 프로필에서 없으면 서버가 뜨지 않는다 |
| `COPAGE_MANAGEMENT_PORT`, `COPAGE_MANAGEMENT_ADDRESS` | 8081, 127.0.0.1 | 지표(`/actuator/prometheus`)와 상태 확인(`/actuator/health/liveness` 등) |
| `COPAGE_DB_SOCKET_TIMEOUT_SECONDS`, `COPAGE_DB_CONNECT_TIMEOUT_MS` | 10, 3000 | DB 가 답하지 않을 때의 한도 |
| `COPAGE_REDIS_TIMEOUT`, `COPAGE_REDIS_CONNECT_TIMEOUT` | 2s, 2s | Redis 가 답하지 않을 때의 한도. 넘으면 503 |
| `COPAGE_BACKEND` | `http://localhost:8080` | Vite 개발 서버가 `/api`, `/ws` 를 넘길 곳 |

API 를 직접 불러 보려면 이렇게 한다(docs/09-API.md).

```bash
curl -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"correct horse 1","name":"김코"}'
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"correct horse 1"}'
# {"accessToken":"...","tokenType":"Bearer","expiresIn":900,"user":{...}}  Refresh 토큰은 쿠키로 온다

curl -X POST localhost:8080/api/workspaces -H 'Authorization: Bearer ...' -H 'Content-Type: application/json' -d '{"name":"내 공간"}'
# {"id":"<wsId>","name":"내 공간","role":"OWNER"}
curl -X POST localhost:8080/api/workspaces/<wsId>/pages -H 'Authorization: Bearer ...'
# {"id":"<pageId>",...,"level":"FULL"}   페이지 ID 가 곧 문서 ID 다
curl -X POST localhost:8080/api/docs/<pageId>/ws-ticket -H 'Authorization: Bearer ...'
# {"ticket":"...","role":"FULL"}

# 30초 안에 연결하고 hello 를 보낸다 (docs/05-PROTOCOL.md)
# ws://localhost:8080/ws/docs/<pageId>?ticket=...&v=1
# {"t":"hello","clientId":"tab-1","lastRev":0,"resume":false}
```

## ErrDeck 으로 모니터링(로컬)

같은 사람이 만든 모니터링 서비스 [ErrDeck](https://github.com/dekim2018/ErrDeck) 에 에러, 성능 추적, 지표를 보낼 수 있다(D-53). 기본으로는 꺼져 있고 CI 는 SDK 없이 빌드한다. ErrDeck 저장소가 같은 상위 폴더(`../ErrDeck`)에 있다고 본다.

```bash
# ErrDeck 쪽: SDK 를 로컬 메이븐에 넣고, ErrDeck 을 띄우고, CoPage 프로젝트와 DSN 을 만든다
(cd ../ErrDeck && ./gradlew :core:publishToMavenLocal :sdk-spring:publishToMavenLocal \
  && docker compose --profile core --profile obs up -d --build \
  && docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql \
  && docker compose exec -T postgres psql -U errdeck < scripts/seed/copage-local.sql)   # 마지막 출력이 DSN 두 개

# 서버: SDK 를 넣어 빌드하고 DSN 을 준다. 관리 포트를 0.0.0.0 으로 열어야 ErrDeck 의 프로메테우스(도커 안)가 지표를 긁는다
./gradlew -Perrdeck=true :app:bootJar
ERRDECK_DSN=http://{키}@localhost:18080/{copage-app 프로젝트} COPAGE_MANAGEMENT_ADDRESS=0.0.0.0 COPAGE_JWT_SECRET=... \
  java -jar app/build/libs/app-0.1.0-SNAPSHOT.jar

# 화면
cd frontend && VITE_ERRDECK_DSN=http://{키}@localhost:18080/{copage-web 프로젝트} npm run dev
```

ErrDeck 콘솔(`../ErrDeck/web` 의 `npm run dev`, 5173)의 전체 보기에 CoPage 의 이슈와 요약 카드가, 성능 화면에 화면의 페이지 이동부터 서버 요청까지 이어진 추적이 보인다. 화면 SDK 를 고쳤으면 `npm run errdeck:sync` 로 다시 가져온다. e2e 가 5174 를 쓰므로 확인용 화면은 다른 포트로 띄운다.

## 빌드와 테스트

JDK 25와 Docker가 필요하다. `app` 의 테스트는 Testcontainers로 PostgreSQL 17, Redis 7, Toxiproxy를 띄운다. 시험 목록 전체와 돌리는 법은 [docs/11-TEST-PLAN.md](docs/11-TEST-PLAN.md) 13.1.1 에 있다.

```bash
./gradlew build
```

`ot-core` 의 무작위 테스트는 기본 횟수가 적다. 오래 돌리려면 배수를 준다. CI 는 10배로 돌린다.

```bash
./gradlew :ot-core:test --rerun-tasks -Dcopage.ot.scale=100
```

## ot-core 테스트가 확인하는 것

| 테스트 | 확인하는 것 |
| --- | --- |
| `DeltaTest` | 텍스트 편집의 적용, 변환, 합성, 역연산. 블록을 나누는 위치와 편집을 가르는 규칙이 서로 맞는지 |
| `ConcurrentEditExamplesTest` | 설계 문서의 변환 규칙을 사례 하나씩으로. Enter와 타이핑이 겹칠 때, Backspace와 타이핑이 겹칠 때 등 |
| `TransformerPropertyTest` | 무작위 문서와 무작위 연산 두 묶음을 어느 순서로 적용해도 같은 문서가 되는지 (TP1) |
| `InverterPropertyTest` | 묶음을 적용하고 역연산을 적용하면 보이는 내용이 원래대로 돌아오는지 |
| `SyncScenarioTest` | 재접속, 중복 전송, 거부 같은 서버와 클라이언트 사이의 순서를 사례로 |
| `ConvergenceSimulationTest` | 서버 하나와 클라이언트 2~5개를 가짜 네트워크로 잇고 편집, 지연, 끊김, 재접속을 무작위로 섞은 뒤 모두 같은 문서가 되는지 |
| `FractionalIndexTest` | 블록 순서 키를 어디에 끼워 넣어도 순서가 유지되는지 |

무작위 테스트는 실패하면 시드를 출력한다. 같은 시드로 다시 돌리면 같은 사례가 나온다.

## 화면 테스트

Playwright가 백엔드 jar와 Vite 개발 서버를 띄우고 브라우저 두 개로 같은 문서를 편집한다. PostgreSQL과 Redis가 떠 있어야 한다. 시험은 24개다.

```bash
docker compose up -d
./gradlew :app:bootJar
cd frontend
npx playwright install chromium
npm run e2e
```

백엔드는 18081, 화면은 5174 포트에 뜬다. 다른 것이 쓰고 있으면 `COPAGE_E2E_BACKEND_PORT`, `COPAGE_E2E_WEB_PORT` 로 바꾼다. DB 와 Redis 포트를 바꿨으면 `COPAGE_DB_PORT`, `COPAGE_REDIS_PORT` 도 같이 준다.

| 테스트 | 확인하는 것 |
| --- | --- |
| 앞과 끝을 동시에 | 같은 문단의 앞과 끝을 동시에 고치면 둘 다 반영된다 |
| Enter와 타이핑 | Enter 뒤쪽에 다른 사람이 치던 글자가 새 블록을 따라간다 |
| Backspace와 타이핑 | 합쳐지는 블록에 다른 사람이 치던 글자가 합쳐진 자리로 간다 |
| 한글 조합 | 조합하는 동안 다른 사람이 편집해도 조합이 깨지지 않는다. 입력기 조합은 Chrome DevTools Protocol로 흉내 낸다 |
| 새로고침 | 로그인(Refresh 쿠키)과 내용이 남는다 |
| 블록 10종 | 마크다운 단축 입력과 도구 막대로 만든 블록 10종이 두 화면에 같게 보인다 |
| 서식과 링크 | 굵게 하는 동안 상대가 그 범위에 친 글자가 남는다. 허용하지 않는 링크 주소는 막는다 |
| 들여쓰기와 체크 | Tab, Shift+Tab, 할 일 체크가 상대 화면에도 보인다 |
| 토글 접기 | 접으면 내 화면에서만 자식이 숨는다 |
| 접속자 목록 | 같은 문서를 연 사람의 이름이 보이고 나가면 사라진다 |
| 남의 커서 | 이름표와 함께 같은 자리에 보이고, 내가 앞에 글자를 치면 따라 밀린다 |
| 큰 붙여넣기 | 64KB 를 넘는 붙여넣기를 여러 묶음으로 나눠 보내 끊기지 않는다 |
| 빠른 타이핑 | 초당 50타로 쳐도 서버 한도에 걸려 끊기지 않는다 |
| 워크스페이스와 트리 | 워크스페이스와 페이지를 만들면 제목이 트리와 경로에 보이고, 남은 열지 못한다. 게스트만인 사용자의 첫 화면 |
| 공유와 회수 | 공유 창과 멤버 화면으로 권한을 주고 내리면 상대 편집기에 1초 안에 적용된다. 회수하면 닫힌다 |
| 보관함 | 구조 충돌로 묶음이 거부되어도 다른 블록의 글은 남고, 못 살린 편집은 보관함에 보인다 |

겹치는 편집이 정말 겹치도록, 한쪽 브라우저로 가는 WebSocket 메시지를 잠시 붙잡은 채 양쪽이 편집하게 한다.

## app 테스트가 확인하는 것

| 테스트 | 확인하는 것 |
| --- | --- |
| `DocumentSessionTest` | 문서 세션의 규칙을 네트워크 없이. 동시 편집의 변환, 묶음 커밋, 커밋 실패 시 세션 버리기, 재접속과 중복 순번, 연결별 순번 규칙, 기록 창, 오래된 연결의 `rebase: false`, 연결 한도, 거부, 제목 알림 |
| `ChangePolicyTest` | 권한 등급별로 허용하는 연산, 블록 종류와 서식 이름, 블록 속성 값, 순서 키, 링크 주소, 문서 크기 한도 |
| `ProtocolCodecTest`, `OpJsonVectorTest` | 메시지 형식. 적합성 벡터의 연산과 문서를 읽었다 다시 쓰면 원래 JSON과 같은지 |
| `JdbcDocumentStoreTest`, `SnapshotReplayPropertyTest` | PostgreSQL 저장소. 묶음 쓰기와 읽기, 제약 위반이면 한 행도 남지 않는지, 스냅샷과 그 뒤 로그로 연 문서가 처음부터 재생한 문서와 같은지(무작위) |
| `RestartTest` | 서버를 완전히 내린 뒤 새로 띄운 서버에서 같은 문서를 받는지 |
| `WebSocketConnectionTest`, `CollabWebSocketHandlerTest` | 받는 쪽이 느린 연결이 보내는 쪽을 막지 않고 4011로 끊기는지. change 와 presence 의 빈도 한도 |
| `CollabWebSocketIntegrationTest` | 실제 WebSocket으로 클라이언트 3개가 무작위로 편집하고 끊겼다 붙은 뒤 모두 서버와 같은 문서가 되는지, 종료 코드 |
| `AuthFlowTest`, `OriginCheckTest`, `RedisRefreshTokenStoreTest` | 가입부터 WebSocket까지. Refresh 토큰 회전과 재사용 시 모든 세션 끊기, 로그아웃, 잠금, Origin, 위조 토큰, iss와 aud |
| `WorkspaceFlowTest`, `JdbcWorkspaceRepositoryTest`, `RemoveMemberAtomicityTest`, `V3MigrationTest`, `TitleNormalizationTest`, `PageTitleTest` | 워크스페이스, 멤버, 페이지 트리, 마지막 OWNER, 기존 문서 옮기기, 제목 동기화 |
| `PermissionRulesTest`, `PagePermissionsTest`, `PermissionFlowTest`, `GrantFlowTest`, `LiveRevocationTest` | 권한 계산 규칙, 티켓의 404와 403, restricted, GUEST, 부여와 회수, 열린 연결에 1초 안에 적용 |
| `RedisOutageTest`, `PostgresOutageTest`, `SlowClientTest`, `DbLatencyTest` | 장애 실험 ([docs/CHAOS.md](docs/CHAOS.md)) |
| `StoreUnavailableFilterTest`, `MetricsTest` | 저장소에 닿지 못한 요청의 503 변환. 지표가 관리 포트에만 열리는지 |
| `ModuleBoundaryTest` | 모듈 경계 (ArchUnit) |

`app` 의 테스트는 적합성 벡터를 쓰므로 `./gradlew :app:test` 가 `:ot-core:exportVectors` 를 먼저 돌린다.

## TypeScript 구현과 적합성 벡터

`frontend/src/collab/ot/` 는 `ot-core` 와 같은 규칙을 TypeScript로 옮긴 것이다. 두 구현이 같은지는 적합성 벡터로 확인한다. Java 구현이 (입력, 기대 결과)를 JSON으로 내보내고, Vitest가 같은 입력을 TypeScript 구현으로 계산해 비교한다 ([ADR 0009](docs/adr/0009-two-implementations-with-vectors.md)).

벡터는 저장소에 넣지 않고 그때그때 만든다. JDK 25와 Node.js 22.12 이상이 필요하다.

```bash
./gradlew :ot-core:exportVectors   # ot-core/build/vectors 에 벡터를 만든다
cd frontend
npm ci
npm run typecheck
npm test
```

| 파일 | 건수 | 비교하는 것 |
| --- | --- | --- |
| `delta.json` | 3,000 | 텍스트 편집의 정규화, 적용, 변환(양쪽 순서), 합성, 역연산, `splitAt`, `transformPosition`. 이모지(코드 단위 2개)를 섞었다 |
| `apply.json` | 1,000 | 문서에 묶음을 적용한 결과와 화면 순서. 적용할 수 없는 묶음이 섞여 있다 |
| `transform.json` | 4,000 | 묶음 두 개의 변환 결과, 변환 충돌 여부, 두 순서로 적용한 문서 |
| `invert.json` | 2,000 | 묶음의 역연산과 역연산을 적용한 문서 |
| `fractional-index.json` | 4,100 | 순서 키 만들기. 잘못된 입력과, 맨 뒤와 맨 앞 추가를 길게 이어 가는 사례(D-24)를 섞었다 |
| `client.json` | 500 | 수렴 시뮬레이션에서 클라이언트가 받은 호출(edit, poll, change, ack, nack, resync)을 차례로 재생하며 호출마다 상태 비교 |

벡터는 시드가 고정되어 있어 같은 코드에서는 같은 파일이 나온다. 어긋나면 Vitest가 시드와 함께 기대값과 실제값을 보여 준다. 생성기는 `ot-core/src/test/java/com/copage/ot/vectors/` 에 있다.

## 부하 도구

`loadgen` 은 실제 서버에 가상 편집자를 붙여 ack 지연, 남의 화면 반영 지연, 거부와 끊김, 수렴을 잰다. 서버를 먼저 띄운다.

```bash
docker compose up -d && ./gradlew :app:bootJar
java -Xmx1g -jar app/build/libs/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev &
./gradlew :loadgen:run --args="scenario=one-doc editors=50 seconds=60"
./gradlew :loadgen:run --args="scenario=many-docs docs=500 editors=4 seconds=60 rate=1"
./gradlew :loadgen:run --args="scenario=open-doc blocks=2000 opens=20 cold=true"
```

시나리오와 인자는 [docs/11-TEST-PLAN.md](docs/11-TEST-PLAN.md) 13.3, 노드 강제 종료 실험은 [docs/CHAOS.md](docs/CHAOS.md) 에 있다.

## 문서

[docs/README.md](docs/README.md)에서 시작한다.

- 설계: 변환 규칙 [docs/04-OT-DESIGN.md](docs/04-OT-DESIGN.md), 규약 [docs/05-PROTOCOL.md](docs/05-PROTOCOL.md), API [docs/09-API.md](docs/09-API.md), 결정과 버린 대안 [docs/adr/](docs/adr/README.md)
- 장애 실험 결과: [docs/CHAOS.md](docs/CHAOS.md)
- 아키텍처 진단과 개선 기록: [ARCHITECTURE-REVIEW](docs/review/ARCHITECTURE-REVIEW.md), [SECURITY-REVIEW](docs/review/SECURITY-REVIEW.md), [DATA-CONSISTENCY-REVIEW](docs/review/DATA-CONSISTENCY-REVIEW.md), [PERFORMANCE-REVIEW](docs/review/PERFORMANCE-REVIEW.md), [IMPLEMENTATION-REPORT](docs/review/IMPLEMENTATION-REPORT.md), [IMPROVEMENT-ROADMAP](docs/review/IMPROVEMENT-ROADMAP.md), 결정 기록 [DECISIONS](docs/review/DECISIONS.md)

## 작업 방식

1. 이슈를 먼저 만든다.
2. `develop` 에서 `feature/번호-작업이름` 브랜치를 만든다. 번호는 작업마다 1씩 올린다 (`feature/01-ot-core`).
3. PR은 `develop` 으로 보내고, 본문에 `Closes #이슈번호` 를 적어 병합되면 이슈가 닫히게 한다.
