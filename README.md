# CoPage

여러 사람이 한 페이지를 동시에 고쳐도 모두 같은 결과를 보게 되는 블록 기반 문서 서비스다. 동시 편집 충돌은 서버 주도 OT(Operational Transformation)를 Java로 직접 구현해서 푼다.

Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다.

## 지금 상태

1단계(OT 코어)까지 구현했고 2단계(협업 서버)를 진행하고 있다. Java 구현과 TypeScript 구현이 적합성 벡터 11,000건에서 같은 결과를 낸다. 협업 서버는 문서 세션, WebSocket 규약, PostgreSQL 연산 로그와 스냅샷까지 있다. 서버를 다시 띄워도 문서가 남는다. 전체 계획은 [docs/12-ROADMAP.md](docs/12-ROADMAP.md)에 있다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 0 | 검증 실험 (편집기 어댑터 시제품) | 편집기 어댑터 완료(문단 블록, 브라우저 두 개의 동시 편집, 한글 조합). pg_bigm 확인은 아직 |
| 1 | OT 코어 (Java, TypeScript, 적합성 벡터) | 완료 |
| 2 | 협업 서버 | 진행 중. 문서 세션, WebSocket 규약, 연산 로그와 스냅샷(PostgreSQL), 로그인과 토큰, 블록 10종 편집기 완료. 프레즌스는 아직 |
| 3~7 | 워크스페이스와 권한, 오프라인과 다중 노드, 부가 기능, 측정, 배포 | 아직 |

## 모듈

| 모듈 | 내용 |
| --- | --- |
| `ot-core` | 문서 모델, 연산 7종, 적용, 변환, 합성, 역연산, 서버와 클라이언트의 동기화 규칙. 외부 의존성이 없다 (테스트의 벡터 생성기만 Jackson을 쓴다) |
| `app` | 협업 서버. 협업 모듈(`com.copage.collab`): 문서 세션, WebSocket 규약, 1회용 티켓, PostgreSQL 연산 로그와 스냅샷. 계정 모듈(`com.copage.identity`): 가입, 로그인, JWT와 Refresh 토큰 회전. 토큰과 티켓은 Redis에 둔다 |
| `loadgen` | 부하 도구. 지금은 비어 있다 |
| `frontend` | 프론트엔드 (Vue 3, Vite, Pinia, Tiptap 3). 로그인과 가입, 편집기 화면. `src/collab/` 아래에 OT 코어(`ot`), 상태 기계와 협업 클라이언트(`client`), WebSocket 연결(`transport`), 편집기 어댑터(`adapter`)가 있다. 편집기는 블록 10종, 서식 6종, Tab 들여쓰기, 마크다운 단축 입력을 다룬다 |

## 빌드와 테스트

JDK 25와 Docker가 필요하다. `app` 의 테스트는 Testcontainers로 PostgreSQL 17과 Redis 7을 띄운다.

```bash
./gradlew build
```

`ot-core` 의 무작위 테스트는 기본 횟수가 적다. 오래 돌리려면 배수를 준다.

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

## 협업 서버 띄워 보기

PostgreSQL과 Redis를 먼저 띄운다. 5432나 6379 포트를 다른 것이 쓰고 있으면 모든 명령 앞에 `COPAGE_DB_PORT=55432 COPAGE_REDIS_PORT=56379` 처럼 다른 포트를 준다. dev 프로필은 개발용 JWT 키를 쓴다. 그 밖의 환경에서는 `COPAGE_JWT_SECRET` 에 32바이트 이상의 키를 주지 않으면 서버가 뜨지 않는다.

```bash
docker compose up -d
./gradlew :app:bootRun --args='--spring.profiles.active=dev'

# 다른 터미널에서
curl -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"correct horse 1","name":"김코"}'
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"correct horse 1"}'
# {"accessToken":"...","tokenType":"Bearer","expiresIn":900,"user":{...}}  Refresh 토큰은 쿠키로 온다

# 문서 ID 는 아무 UUID 나 쓴다. 권한 계산(3단계) 전까지는 로그인한 사용자가 어느 문서든 편집할 수 있다.
curl -X POST localhost:8080/api/docs/3f0c6a8e-0000-4000-8000-000000000001/ws-ticket -H 'Authorization: Bearer ...'
# {"ticket":"...","role":"EDIT"}

# 30초 안에 연결하고 hello 를 보낸다 (docs/05-PROTOCOL.md)
# ws://localhost:8080/ws/docs/3f0c6a8e-0000-4000-8000-000000000001?ticket=...&v=1
# {"t":"hello","clientId":"tab-1","lastRev":0,"resume":false}
```

## 편집기 띄워 보기

백엔드를 위처럼 띄운 뒤 Vite 개발 서버를 띄운다. 개발 서버는 `/api` 와 `/ws` 를 백엔드(기본 `http://localhost:8080`, `COPAGE_BACKEND` 로 바꿈)로 넘긴다.

```bash
cd frontend
npm ci
npm run dev
# http://localhost:5173 에서 가입하고 "새 문서" 를 누른다. 주소를 다른 브라우저(다른 사용자)에서 열면 같이 편집된다.
```

## 화면 테스트

Playwright가 백엔드 jar와 Vite 개발 서버를 띄우고 브라우저 두 개로 같은 문서를 편집한다. PostgreSQL과 Redis가 떠 있어야 한다.

```bash
docker compose up -d
./gradlew :app:bootJar
cd frontend
npx playwright install chromium
npm run e2e
```

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

겹치는 편집이 정말 겹치도록, 한쪽 브라우저로 가는 WebSocket 메시지를 잠시 붙잡은 채 양쪽이 편집하게 한다.

## app 테스트가 확인하는 것

| 테스트 | 확인하는 것 |
| --- | --- |
| `DocumentSessionTest` | 문서 세션의 규칙을 네트워크 없이. 동시 편집의 변환, 묶음 커밋, 커밋 실패 시 세션 버리기, 재접속과 중복 순번, 오래된 연결의 `rebase: false`, 연결 한도, 거부 |
| `ChangePolicyTest` | 권한 등급별로 허용하는 연산, 블록 종류와 서식 이름, 링크 주소, 문서 크기 한도 |
| `ProtocolCodecTest`, `OpJsonVectorTest` | 메시지 형식. 적합성 벡터의 연산과 문서를 읽었다 다시 쓰면 원래 JSON과 같은지 (TypeScript 구현과 같은 형식인지) |
| `JdbcDocumentStoreTest` | PostgreSQL 저장소. 묶음 쓰기와 읽기, 제약 위반이면 한 행도 남지 않는지, 스냅샷에 그 뒤 연산을 적용해 여는지, 스냅샷을 세 개만 남기는지 |
| `RestartTest` | 서버를 띄워 편집하고 완전히 내린 뒤 새로 띄운 서버에서 같은 문서를 받는지 (2단계 완료 기준) |
| `WebSocketConnectionTest` | 받는 쪽이 느린 연결이 보내는 쪽을 막지 않고, 대기열이나 시간 한도를 넘으면 4011로 끊기는지 |
| `CollabWebSocketIntegrationTest` | 서버를 띄우고 실제 WebSocket으로. 클라이언트 3개가 무작위로 편집하고 끊겼다 붙은 뒤 모두 서버와 같은 문서가 되는지, 종료 코드 4001, 4008, 4010 |
| `AuthFlowTest` | 가입부터 티켓을 받아 WebSocket에 붙기까지. Refresh 토큰 회전과 재사용 시 모든 세션 끊기, 로그아웃, 5회 실패 잠금, 허용하지 않은 Origin, 위조 토큰 |

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
| `fractional-index.json` | 500 | 순서 키 만들기. 잘못된 입력도 섞었다 |
| `client.json` | 500 | 수렴 시뮬레이션에서 클라이언트가 받은 호출(edit, poll, change, ack, nack, resync)을 차례로 재생하며 호출마다 상태 비교 |

벡터는 시드가 고정되어 있어 같은 코드에서는 같은 파일이 나온다. 어긋나면 Vitest가 시드와 함께 기대값과 실제값을 보여 준다. 생성기는 `ot-core/src/test/java/com/copage/ot/vectors/` 에 있다.

## 문서

[docs/README.md](docs/README.md)에서 시작한다. 변환 규칙은 [docs/04-OT-DESIGN.md](docs/04-OT-DESIGN.md), 결정과 버린 대안은 [docs/adr/](docs/adr/README.md)에 있다.

## 작업 방식

1. 이슈를 먼저 만든다.
2. `develop` 에서 `feature/번호-작업이름` 브랜치를 만든다. 번호는 작업마다 1씩 올린다 (`feature/01-ot-core`).
3. PR은 `develop` 으로 보내고, 본문에 `Closes #이슈번호` 를 적어 병합되면 이슈가 닫히게 한다.
