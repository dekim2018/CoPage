# ADR 0014. Redis 에 닿지 못하면 막고(503), 생존 검사는 Redis 를 보지 않는다

- 상태: 확정
- 결정일: 2026-11-23
- 관련 문서: [07-SECURITY.md](../07-SECURITY.md) 9.7, [03-ARCHITECTURE.md](../03-ARCHITECTURE.md) 5.6, [11-TEST-PLAN.md](../11-TEST-PLAN.md) 13.4, [review/DECISIONS.md](../review/DECISIONS.md) D-03, D-25

## 고른 것

막힘(fail-closed). Redis 가 필요한 요청(Access 토큰 검증, 로그인, 재발급, 로그아웃, 티켓 발급, WebSocket 핸드셰이크)은 503 과 `Retry-After: 5` 로 거절한다. 이미 열린 편집 연결은 Redis 를 쓰지 않으므로 그대로 둔다. 생존 검사는 `/actuator/health/liveness` 로 하고, 이것은 Redis 와 DB 를 보지 않는다.

## 버린 대안

- 열림(fail-open): Redis 에 닿지 못하면 Access 토큰의 차단 목록 확인만 건너뛴다.
- 생존 검사에 묶은 `/actuator/health` 를 쓴다.

## 이유

- 열림으로 두면 로그아웃한 토큰과 Refresh 토큰 재사용으로 막은 사용자의 토큰이 장애 동안 다시 통한다. 보안 판단을 Redis 없이 하지 않는다.
- 401 로 답하면 화면이 로그인이 끝난 것으로 본다. 503 이면 화면은 Access 토큰을 지우지 않고 일시적 실패로 다룬다.
- 편집 경로(편집, ack, remote, presence)는 Redis 를 쓰지 않으므로 열린 연결까지 끊을 이유가 없다.
- 묶은 health 는 Redis 를 보므로 생존 검사에 쓰면 Redis 장애가 모든 노드의 재시작으로 번지고, 열린 편집 연결까지 끊긴다. 그러면 이 정책의 뜻이 사라진다.

## 감수하는 것

- Redis 장애 동안 새 로그인, 재발급, 새 연결, 재접속, 토큰이 필요한 모든 API 가 멈춘다. 15분 안에 Access 토큰이 만료된 사용자는 장애가 끝날 때까지 API 를 쓰지 못한다.
- 시간 초과로 포기한 Redis 명령도 Redis 가 돌아오면 실행된다. Refresh 토큰 회전은 기한을 붙인 스크립트 하나로 해서 늦은 실행이 세션을 잃게 하지 않는다(D-08).

## 확인

`RedisOutageTest` 가 Redis 컨테이너를 일시 정지해 503, 열린 연결의 계속 편집, liveness 200, health 503, 되살린 뒤 재발급을 확인한다 ([CHAOS.md](../CHAOS.md)).

## 다시 검토할 조건

Redis 장애가 운영에서 자주 나 사용자가 막히는 시간이 보안 위험보다 크다고 보이면 이 문서를 고치고 상태를 "대체됨"으로 바꾼다. 다중 노드(4단계)에서 리스를 Redis 에 두면 열린 편집도 Redis 를 쓰게 되므로 그때 다시 본다.
