# 결정 기록 (ADR)

결정 하나에 파일 하나를 둔다. 결정을 바꿀 때는 기존 파일을 지우지 않고 상태를 "대체됨"으로 바꾼 뒤 새 번호로 추가한다.

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
