# 13. 테스트와 검증

이 프로젝트의 버그는 "가끔, 특정 순서로 겹칠 때만" 나타난다. 사람이 사례를 생각해 적는 테스트로는 잡을 수 없어서, 무작위로 대량 생성한 상황에서 "모두 같은 결과가 되었는가"를 검사하는 방식을 중심에 둔다.

## 13.1 테스트 단계

| 단계 | 대상 | 방법 | 통과 기준 |
| --- | --- | --- | --- |
| 속성 테스트 | `ot-core` 변환 함수 | 무작위 문서 S와 무작위 연산 a, b를 만들어, a 뒤에 변환된 b를 적용한 결과와 b 뒤에 변환된 a를 적용한 결과를 비교 | 항상 같다 (TP1) |
| 속성 테스트 | 합성, 역연산 | 합성한 연산을 한 번 적용한 결과와 차례로 적용한 결과 비교. 연산 뒤 역연산을 적용하면 원래 문서 | 항상 같다 |
| 수렴 시뮬레이션 | 서버 세션 로직 + 클라이언트 상태 기계 | 가짜 네트워크로 클라이언트 2~5개를 연결하고, 편집, 지연, 순서 뒤바뀜 없는 단절, 재접속을 무작위로 섞어 실행. 끝나면 모든 메시지를 흘려보낸 뒤 비교 | 모든 클라이언트 문서 = 서버 문서 = 로그를 처음부터 적용한 문서 |
| 적합성 벡터 | Java 구현과 TypeScript 구현의 일치 | Java 쪽에서 (문서, a, b, 변환 결과, 적용 결과)를 JSON 파일로 1만 건 내보내고 Vitest가 같은 입력으로 계산해 비교 | 전부 같다 |
| 통합 테스트 | 커밋, 멱등성, 권한, 장애 (리스는 4단계) | Testcontainers로 PostgreSQL과 Redis를 띄우고 실제 WebSocket으로 검사 | 사례별 단언 |
| 화면 테스트 | 편집기 어댑터 | Playwright로 브라우저 두 개를 띄워 동시 입력, Enter와 Backspace 겹침, 한글 조합 중 원격 편집 | 두 화면의 문서가 같다 |

실패한 무작위 사례는 시드 값을 로그에 남겨 그대로 다시 실행할 수 있게 한다. 한 번 발견한 사례는 고정 테스트로 옮겨 다시 생기지 않게 한다.

### 13.1.1 지금 있는 시험과 돌리는 법

| 묶음 | 시험 | 확인하는 것 | 돌리는 법 |
| --- | --- | --- | --- |
| OT 단위와 사례 | `DeltaTest`, `ConcurrentEditExamplesTest`, `SyncScenarioTest`, `FractionalIndexTest` | 텍스트 편집의 적용, 변환, 합성, 역연산. 설계의 변환 규칙 사례. 재접속, 중복, 거부 순서. 순서 키 | `./gradlew :ot-core:test` |
| OT 속성 | `TransformerPropertyTest`(TP1, 기본 100,000쌍), `InverterPropertyTest`(30,000회), `ConvergenceSimulationTest`(1,500회, 클라이언트 2~5개, 단계 40~199개) | 두 순서의 결과가 같은지, 역연산이 되돌리는지, 모두 같은 문서로 수렴하는지. 생성기(`RandomOps`, test fixtures)는 이모지, 국기, 긴 글을 섞는다(D-31) | `-Dcopage.ot.scale=N` 으로 N 배. CI 는 10배 |
| 적합성 벡터 | `ot-core` 의 `VectorExporter` → `frontend/test/conformance.test.ts`, `OpJsonVectorTest` | Java 와 TypeScript 구현의 같음. 연산 JSON 형식 | `./gradlew :ot-core:exportVectors` 뒤 `cd frontend && npm test` |
| 서버 단위 | `DocumentSessionTest`, `ChangePolicyTest`, `ProtocolCodecTest`, `WebSocketConnectionTest`, `CollabWebSocketHandlerTest`, `PermissionRulesTest`, `PagePermissionsTest`, `TitleNormalizationTest`, `OriginCheckTest`, `StoreUnavailableFilterTest` | 세션 규칙(묶음 커밋, 순번 규칙, 세션 버리기, 기록 창), 연산 단위 인가와 입력 검사, 느린 연결, 권한 규칙, 503 변환 | `./gradlew :app:test` |
| 저장소 | `JdbcDocumentStoreTest`, `SnapshotReplayPropertyTest`, `JdbcWorkspaceRepositoryTest`, `V3MigrationTest`, `RedisRefreshTokenStoreTest` | 실제 PostgreSQL, Redis. 스냅샷과 로그 재생의 일치를 무작위로, 기존 문서를 옮기는 마이그레이션 | 위와 같음. Docker 가 필요하다 |
| 통합 | `CollabWebSocketIntegrationTest`, `RestartTest`, `AuthFlowTest`, `WorkspaceFlowTest`, `PermissionFlowTest`, `GrantFlowTest`, `RemoveMemberAtomicityTest`, `LiveRevocationTest`, `PageTitleTest`, `MetricsTest` | 실제 서버와 WebSocket. 무작위 편집과 끊김 뒤 수렴, 재시작 뒤 문서, 인증 흐름, 권한 표, 연결 중 회수 1초, 제목 동기화, 지표 | 위와 같음 |
| 장애 | `RedisOutageTest`, `PostgresOutageTest`, `SlowClientTest`, `DbLatencyTest`(Toxiproxy) | 13.2 의 실험. 결과는 [CHAOS.md](./CHAOS.md) | 위와 같음 |
| 모듈 경계 | `ModuleBoundaryTest` (ArchUnit) | 5.1 의 모듈 규칙 (D-32) | 위와 같음 |
| 화면 단위 | `src/**/*.test.ts` (Vitest) | 어댑터, 상태 기계 바깥 규칙(보내기 나누기, 살리기, 커서, 등급), 전송의 재접속과 치명 코드, 스토어 | `cd frontend && npm test` |
| 화면 성능 | `render.perf.ts` | 원격 편집 하나 반영 시간(D-26). 시간은 단언하지 않는다 | `cd frontend && npm run perf` |
| 화면(e2e) | `frontend/e2e/*.spec.ts` (Playwright, 24개) | 브라우저 두 개의 동시 편집, 한글 조합, 블록 10종, 64KB 넘는 붙여넣기, 빠른 타이핑, 공유와 회수, 멤버, 보관함, 접속자와 커서 | 아래 |
| 부하와 노드 강제 종료 | `loadgen` | 13.3, 13.2 의 노드 강제 종료와 재접속 몰림 | 13.3 |

`./gradlew build` 가 Java 쪽 시험을 모두 돌린다. `app` 의 시험은 Testcontainers 로 PostgreSQL 17, Redis 7, Toxiproxy 를 띄우므로 Docker 가 있어야 한다.

화면 시험은 PostgreSQL 과 Redis 가 떠 있어야 하고, Playwright 가 백엔드 jar 와 Vite 개발 서버를 띄운다.

```bash
docker compose up -d                     # 포트가 겹치면 COPAGE_DB_PORT, COPAGE_REDIS_PORT
./gradlew :app:bootJar
cd frontend && npx playwright install chromium
COPAGE_E2E_BACKEND_PORT=18081 COPAGE_E2E_WEB_PORT=5174 npm run e2e   # 두 값은 기본값이다
```

CI(`.github/workflows/ci.yml`)는 빌드와 시험, OT 무작위 시험 10배, 화면 타입 검사와 빌드와 단위 시험, npm audit, gitleaks, e2e 를 PR 과 develop, main 에 돌린다.

## 13.2 장애 실험

| 실험 | 방법 | 통과 기준 | 상태 |
| --- | --- | --- | --- |
| 소유 노드 강제 종료 | 편집 부하 중 `docker kill` | ack 받은 묶음이 전부 로그에 있다. 클라이언트가 10초 안에 편집을 재개한다. 최종 문서가 모두 같다 | 노드 1대를 `kill -9` 하고 다시 띄워 확인(loadgen). 다른 노드가 넘겨받는 경우는 4단계 |
| 노드 일시 정지 | 소유 노드에 `docker pause` 10초 후 `unpause` (리스 6초보다 길게) | 깨어난 노드의 커밋이 유일 제약으로 실패하고 세션을 버린다. 로그에 같은 리비전이 두 개 없다 | 다중 노드(4단계)와 함께 |
| 노드 추가 | 편집 부하 중 노드를 2대에서 3대로 | 양도된 문서의 클라이언트가 재접속 한 번으로 이어서 편집한다. 유실 0건 | 다중 노드(4단계)와 함께 |
| DB 지연 | PostgreSQL 응답에 200ms 지연 (`tc netem` 대신 Toxiproxy) | 묶음 커밋으로 처리량이 유지된다. ack 없는 묶음이 남에게 보이지 않는다 | `DbLatencyTest` |
| Redis 중단 | Redis 컨테이너 일시 정지 | (리스가 아직 없어 기준을 바꿨다) Redis 가 필요한 요청은 503, 열린 편집 연결은 계속, 생존 검사는 200, 되살리면 다시 된다 (9.7). 리스가 생기면 원래 기준을 더한다 | `RedisOutageTest` |
| PostgreSQL 중단 | PostgreSQL 컨테이너 일시 정지 | (처음 표에 없던 실험) 세션이 시간 한도 뒤 버려지고(4009), API 는 503, 되살린 뒤 보내지 못한 편집이 한 번만 확정된다 | `PostgresOutageTest` |
| 클라이언트 단절 | 무작위 편집 중 클라이언트가 무작위로 끊고 다시 붙는다 (프록시 대신) | 같은 묶음이 두 번 적용되지 않는다. 빠지는 묶음도 없다 | `CollabWebSocketIntegrationTest` |
| 느린 클라이언트 | 한 연결의 수신을 멈춤 | 그 연결만 4011로 끊긴다. 다른 사람의 ack 지연이 늘지 않는다 | `SlowClientTest` |

실험은 스크립트(`scripts/chaos/`) 대신 자동 시험으로 만들어 CI 에서 계속 돈다. 노드 강제 종료만 손으로 돌리는 `loadgen` 실험이다. 결과와 실험으로 찾아 고친 결함은 [CHAOS.md](./CHAOS.md) 에 있다.

## 13.3 부하 측정

`loadgen` 의 가상 클라이언트는 실제 클라이언트와 같은 상태 기계(`ot-core` 의 `ClientSession`)와 같은 보내기 간격(50ms)을 쓴다. 타이핑 속도는 `rate`(기본 초당 5타)로 정한다. Enter, Backspace, 블록 이동의 비율을 정하는 시나리오는 아직 없다.

```bash
docker compose up -d && ./gradlew :app:bootJar
java -Xmx1g -jar app/build/libs/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev &
./gradlew :loadgen:run --args="scenario=one-doc editors=50 seconds=60"
./gradlew :loadgen:run --args="scenario=many-docs docs=500 editors=4 seconds=60 rate=1"
./gradlew :loadgen:run --args="scenario=open-doc blocks=2000 opens=20 cold=true"
```

`base`(기본 `http://localhost:8080`), `management`(기본 `http://127.0.0.1:8081`), `reconnect=true`(끊기면 다시 붙는다, 노드 강제 종료 실험)를 더 줄 수 있다. 지표는 관리 포트에서 읽는다.

| 시나리오 | 구성 | 보는 것 | 상태 |
| --- | --- | --- | --- |
| 한 문서 집중 | 문서 1개에 편집자 10, 25, 50, 100명 | ack 지연, 변환 횟수, 거부율이 인원에 따라 어떻게 늘어나는지 | one-doc. 50명 ack p99 9.1ms, 남의 화면 반영 p99 9.3ms. 100명 ack p99 47.2ms(PERF-2 전 125.8ms) |
| 여러 문서 분산 | 문서 500개에 편집자 2~4명씩 | 노드 1대의 메모리와 CPU, 문서 세션 1개당 메모리 | many-docs. 문서 500 x 4명(연결 2,000)이 힙 1GB 에서 돈다. 정점의 살아 있는 힙 437MB (D-36) |
| 문서 열기 | 블록 100, 1,000, 5,000개 문서를 스냅샷 간격 50, 200, 1,000으로 | 열기 시간과 저장 용량의 교환 관계 | open-doc. 2,000 블록 첫 열기 93ms, 세션이 올라 있을 때 p95 25.5ms. 스냅샷 간격을 바꾼 비교는 아직 |
| 커밋 방식 비교 | 묶음마다 커밋과 묶음 커밋 | 초당 확정 묶음 수, ack p99 | 따로 돌리지 않았다. 커밋 경로를 왕복 한 번으로 바꾼 전후를 `DbLatencyTest` 와 one-doc 으로 비교했다(D-38) |
| 재접속 몰림 | 노드 종료 후 클라이언트 1,000개가 동시에 재접속 | 복구 시간, 백오프의 무작위 지연이 효과가 있는지 | many-docs `reconnect=true` 로 1,000개. 모두 이어 붙고 p99 6.3초 ([CHAOS.md](./CHAOS.md)) |

수치는 노트북 한 대에서 서버(힙 1GB)와 loadgen 을 함께 돌린 값이다. 자세한 조건과 해석은 [review/PERFORMANCE-REVIEW.md](./review/PERFORMANCE-REVIEW.md) 에 있다.

## 13.4 남길 지표

운영 중에도 같은 지표를 Micrometer로 내보낸다. 목표값은 1.4의 성공 기준과 같다.

내보내는 곳은 관리 포트의 `/actuator/prometheus` 다(기본 8081, 이 기기에서만, `COPAGE_MANAGEMENT_PORT`, `COPAGE_MANAGEMENT_ADDRESS`). API 포트로는 열지 않는다. 분포는 히스토그램으로 내고 백분위는 수집하는 쪽에서 `histogram_quantile` 로 계산한다. 레이블에 문서 ID 와 사용자 ID 를 넣지 않는다(D-25). 아래 표에서 리스 충돌만 없다. 리스(다중 노드, 4단계)가 생길 때 더한다.

상태 확인은 셋이다. 생존 검사(재시작 판단)에는 `/actuator/health/liveness` 만 쓴다. 이것은 앱 자신만 보고 DB 와 Redis 를 보지 않는다. 트래픽을 보낼지는 `/actuator/health/readiness` 로 본다. 묶은 `/actuator/health` 는 DB 와 Redis 를 함께 보므로 Redis 장애 때 DOWN(503)이다. 이것을 생존 검사로 쓰면 Redis 장애가 모든 노드의 재시작으로 번지고, 열린 편집 연결이 끊긴다(9.7, D-03). 컨테이너에서 수집기가 닿도록 `COPAGE_MANAGEMENT_ADDRESS=0.0.0.0` 으로 열 때는 관리 포트를 공개 네트워크에 내지 않는다(`RedisOutageTest` 가 정지 중 liveness 200, health 503 을 확인한다).

| 지표 | 이름 | 보는 이유 |
| --- | --- | --- |
| ack 지연 | `collab.ack.latency` (p50, p95, p99) | 사용자가 느끼는 저장 속도 |
| 묶음당 변환 횟수 | `collab.transform.count` | 동시 편집이 얼마나 겹치는지. 클수록 클라이언트의 기준 리비전이 뒤처져 있다는 뜻 |
| 거부율 | `collab.nack` (사유 분류별: permission, input, empty, rejected. 비율은 ack 횟수와 나눠 본다) | 6.6의 거부 규칙이 실제로 얼마나 자주 사용자 입력을 되돌리는지 |
| 커밋 묶음 크기 | `collab.commit.batch.size` | 묶음 커밋이 동작하는지 |
| 열린 세션, 연결 수 | `collab.sessions`, `collab.connections` | 노드 수용량 |
| 세션 복구 시간 | `collab.session.load` | 스냅샷 간격 조정 근거 |
| 리스 충돌 | `collab.lease.conflict` | 두 노드가 겹친 횟수. 0에 가까워야 한다 |
| 연산 크기 | `collab.op.bytes` | 저장 용량 예측 |
