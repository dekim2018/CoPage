# CoPage

여러 사람이 한 페이지를 동시에 고쳐도 모두 같은 결과를 보게 되는 블록 기반 문서 서비스다. 동시 편집 충돌은 서버 주도 OT(Operational Transformation)를 Java로 직접 구현해서 푼다.

Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다.

## 지금 상태

1단계(OT 코어)까지 구현했다. Java 구현과 TypeScript 구현이 적합성 벡터 11,000건에서 같은 결과를 낸다. 전체 계획은 [docs/12-ROADMAP.md](docs/12-ROADMAP.md)에 있다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 0 | 검증 실험 (편집기 어댑터 시제품) | 아직 |
| 1 | OT 코어 (Java, TypeScript, 적합성 벡터) | 완료 |
| 2 | 협업 서버 | 아직 |
| 3~7 | 워크스페이스와 권한, 오프라인과 다중 노드, 부가 기능, 측정, 배포 | 아직 |

## 모듈

| 모듈 | 내용 |
| --- | --- |
| `ot-core` | 문서 모델, 연산 7종, 적용, 변환, 합성, 역연산, 서버와 클라이언트의 동기화 규칙. 외부 의존성이 없다 (테스트의 벡터 생성기만 Jackson을 쓴다) |
| `app` | 협업 서버. 지금은 Spring Boot 뼈대만 있다 |
| `loadgen` | 부하 도구. 지금은 비어 있다 |
| `frontend` | 프론트엔드. 지금은 `ot-core` 의 TypeScript 구현(`src/collab/ot/`)과 클라이언트 상태 기계(`src/collab/client/`)만 있다. Vue와 Vite는 2단계에서 붙인다 |

## 빌드와 테스트

JDK 25가 필요하다.

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
