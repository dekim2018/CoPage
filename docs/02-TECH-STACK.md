# 4. 기술 스택과 버전

백엔드는 Java 25와 Spring Boot 4.1 계열, 프론트엔드는 Vue 3와 TypeScript로 간다. 팬파라이브와 같은 버전 계열을 써서 환경 준비 시간을 줄이고, 패치 버전은 착수하는 날의 최신으로 맞춘다.

## 4.1 백엔드

| 영역 | 선택 | 선택 이유 |
| --- | --- | --- |
| 언어 | Java 25 (LTS) | sealed interface와 record, switch 패턴 매칭으로 연산 종류를 타입으로 표현한다. 변환 규칙에서 빠진 조합을 컴파일러가 잡아 준다 |
| 프레임워크 | Spring Boot 4.1.x ([4.1.0은 2026-06-10 출시](https://spring.io/blog/2026/06/10/spring-boot-4/), [4.1.1은 2026-08-20 출시](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)) | Spring Framework 7.0.x, Spring Security 7.1.x, Jackson 3이 함께 들어온다 |
| 실시간 통신 | Spring WebSocket의 `WebSocketHandler` 직접 구현 | 자체 메시지 규약과 ack 규칙이 필요하다. STOMP를 쓰지 않는 이유는 15장 참고 |
| 동시 처리 | 가상 스레드 (`spring.threads.virtual.enabled=true`) | 문서 세션마다 직렬 실행 큐를 하나씩 두어도 스레드 비용이 작다 |
| 보안 | Spring Security 7.1.x | JWT 필터 체인, 메서드 보안(`@PreAuthorize`), WebSocket 핸드셰이크 검사 |
| 데이터 접근 | 협업 경로는 `JdbcClient`, 나머지 CRUD는 Spring Data JPA | 연산 로그는 단순 INSERT가 초당 수백 번 일어나는 경로라 SQL을 직접 제어한다 |
| DB | PostgreSQL 17 + pg_bigm 확장 | 연산 로그의 (문서, 리비전) 유일 제약, JSONB, 재귀 CTE, 두 글자 한글 검색 |
| 캐시와 조정 | Redis 7 | 문서 소유 리스, 1회용 티켓, Refresh 토큰, 노드 간 알림 전달(pub/sub) |
| 스키마 관리 | Flyway | 팬파라이브와 같은 방식 |
| 빌드 | Gradle 멀티 모듈 (`ot-core`, `app`, `loadgen`) | `ot-core`는 Spring 의존성이 없는 순수 Java 라이브러리로 둔다 |
| 모듈 경계 검사 | Spring Modulith | 모듈 간 금지된 참조를 테스트에서 잡는다 |
| 테스트 | JUnit, 시드를 고정한 자체 무작위 생성기, Testcontainers | 속성 기반 테스트에 jqwik을 쓰려 했으나, 외부 라이브러리 없이 시드만 있으면 실패 사례를 그대로 다시 돌릴 수 있어서 직접 만들었다 (`ot-core` 의 `RandomOps`) |
| 관측 | Micrometer + Prometheus 형식 노출 | ack 지연, 변환 횟수, 거부율을 지표로 남긴다. 에러 수집은 3번 프로젝트(에러 모니터링 서비스)의 SDK를 붙인다 |

## 4.2 프론트엔드

| 영역 | 선택 | 선택 이유 |
| --- | --- | --- |
| 프레임워크 | Vue 3 (Composition API, `<script setup>`) + Vite + Pinia + Vue Router | 팬파라이브와 같은 구성 |
| 언어 | TypeScript | OT 클라이언트는 연산 종류별 분기가 많아 타입 검사가 필요하다. 화면 컴포넌트는 JavaScript로 써도 된다 |
| 편집기 | Tiptap 3 (ProseMirror 기반) | 입력, 선택 영역, 한글 조합 처리를 맡긴다. 협업 확장(Yjs)은 쓰지 않고 자체 어댑터를 붙인다 |
| 오프라인 저장 | IndexedDB (`idb` 라이브러리) | 보내지 못한 연산과 마지막 확정 문서를 보관한다 |
| 테스트 | Vitest, Playwright | OT 적합성 벡터 실행, 브라우저 2개를 띄운 동시 편집 시나리오 |

## 4.3 인프라

| 영역 | 선택 |
| --- | --- |
| 실행 | Docker Compose: 프록시 1, 애플리케이션 노드 2, PostgreSQL 1, Redis 1 |
| 프록시 | Caddy. 자동 HTTPS, WebSocket 경로는 문서 ID 해시로 노드를 고른다 |
| 배포 | AWS EC2 4GB 1대 (팬파라이브와 같은 방식), GitHub Actions로 빌드와 테스트 |
| 부하 도구 | `loadgen` 모듈: `ot-core`를 그대로 쓰는 Java 가상 클라이언트. 프로세스 하나가 가상 스레드로 클라이언트 수백 개를 흉내 낸다 |

부하 도구를 k6로 하지 않는 이유는 클라이언트도 받은 연산에 맞춰 자기 연산을 변환해야 하기 때문이다. 변환을 하지 않는 가짜 클라이언트는 곧바로 잘못된 위치를 보내게 되고, 그러면 실제 부하와 다른 것을 재게 된다.
