# 결정 기록 (ADR)

결정 하나에 파일 하나를 둔다. 결정을 바꿀 때는 기존 파일을 지우지 않고 상태를 "대체됨"으로 바꾼 뒤 새 번호로 추가한다. 뒤의 결정이 앞의 결정을 좁히거나 구체화하면 앞 파일 끝의 "후속" 에 적는다. 0013 부터는 아키텍처 진단 개선 작업의 결정([review/DECISIONS.md](../review/DECISIONS.md))에서 왔다.

| 번호 | 결정 | 버린 대안 | 결정일 |
| --- | --- | --- | --- |
| [0001](./0001-server-driven-ot.md) | 동시 편집은 서버 주도 OT로 푼다 | CRDT (Yjs, Automerge) | 2026-11-13 |
| [0002](./0002-id-addressed-blocks.md) | 블록은 ID로 가리키고 블록 안만 텍스트 OT를 쓴다 | 문서 전체를 경로 기반 JSON OT로 | 2026-11-22 |
| [0003](./0003-raw-websocket.md) | STOMP 없이 WebSocketHandler를 직접 구현한다 | STOMP + 메시지 브로커 | 2026-11-22 |
| [0004](./0004-single-owner-node.md) | 한 문서는 한 노드가 소유한다 | 모든 노드가 편집을 받고 Redis pub/sub으로 서로 방송 | 2026-11-13 |
| [0005](./0005-commit-before-ack.md) | ack 전에 DB에 커밋하고 묶음 커밋으로 처리량을 지킨다 | 메모리에만 적용하고 나중에 모아서 저장 | 2026-11-22 |
| [0006](./0006-comment-anchor-as-mark.md) | 댓글 위치는 글자 서식으로 표현한다 | 댓글 테이블에 (블록, 시작, 끝)을 저장하고 편집마다 갱신 | 2026-11-22 |
| [0007](./0007-search-with-pg-bigm.md) | 검색은 PostgreSQL과 pg_bigm으로 한다 | Elasticsearch, OpenSearch | 2026-11-22 |
| [0008](./0008-modular-monolith.md) | 서버는 모듈형 단일 서버로 만든다 | MSA | 2026-11-22 |
| [0009](./0009-two-implementations-with-vectors.md) | OT 코어는 Java와 TypeScript로 각각 구현하고 적합성 벡터로 맞춘다 | 한 언어로 짜서 양쪽에서 실행 (Java를 브라우저용으로 변환하거나 서버에서 JavaScript 실행) | 2026-11-22 |
| [0010](./0010-single-prosemirror-with-adapter.md) | 편집기는 ProseMirror 하나에 어댑터를 붙인다 | 블록마다 따로 떨어진 입력 영역 | 2026-11-22 |
| [0011](./0011-no-time-partition-on-op-log.md) | 연산 로그는 시간 범위 파티션을 나누지 않는다 | 시간 범위 파티션 | 2026-11-22 |
| [0012](./0012-nack-on-structural-conflict.md) | 일부 구조 충돌은 변환하지 않고 거부한다 | 모든 충돌을 변환으로 해소 | 2026-11-22 |
| [0013](./0013-primary-key-as-fence.md) | 펜싱 토큰 없이 연산 로그의 기본 키를 펜스로 쓴다 | 리스의 펜싱 토큰을 모든 쓰기에 싣는다 | 2026-11-23 |
| [0014](./0014-fail-closed-on-redis-outage.md) | Redis 에 닿지 못하면 막고(503), 생존 검사는 Redis 를 보지 않는다 | 토큰 차단 확인만 건너뛰는 열림(fail-open), 묶은 health 로 생존 검사 | 2026-11-23 |
| [0015](./0015-permission-per-request-without-cache.md) | 권한은 요청마다 재귀 CTE 로 계산하고 캐시하지 않으며, 바뀌면 열린 연결에 다시 적용한다 | Redis 권한 캐시, 연결할 때만 검사, `doc_access` 최소 공유 | 2026-11-24 |
| [0016](./0016-per-connection-seq-and-single-insert.md) | 커밋은 연결별 순번 규칙과 INSERT 문 하나로 DB 왕복을 한 번으로 한다 | 묶음마다 SELECT 와 INSERT, JDBC batch, unnest 배열 | 2026-11-24 |
| [0017](./0017-salvage-rejected-edits-per-block.md) | 거부되거나 버려진 편집은 블록 단위로 살리고, 못 살린 글은 보관함에 보인다 | 묶음과 그 위 입력을 통째로 버린다 | 2026-11-24 |
| [0018](./0018-module-boundaries-with-archunit.md) | 모듈 경계는 ArchUnit 시험으로 지킨다 | Spring Modulith | 2026-11-24 |
