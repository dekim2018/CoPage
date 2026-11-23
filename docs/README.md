# 코페이지(가칭) 문서

실시간 공동 편집 문서 서비스의 기획과 설계 문서다. Java 25, Spring Boot 4.1, Spring Security 7.1, Vue 3을 쓴다.

문서 안에서 "6장", "8.4" 같은 표현은 아래 표의 장 번호를 가리킨다.

| 장 | 파일 | 내용 |
| --- | --- | --- |
| 1 | [01-PLAN.md](./01-PLAN.md) | 목표, 확정된 결정, 성공 기준 |
| 2 | [01-PLAN.md](./01-PLAN.md) | 기능 요구사항 40개, 비기능 5개 |
| 3 | [01-PLAN.md](./01-PLAN.md) | 화면 9개와 주요 흐름 3개 |
| 4 | [02-TECH-STACK.md](./02-TECH-STACK.md) | 백엔드, 프론트엔드, 인프라 선택과 이유 |
| 5 | [03-ARCHITECTURE.md](./03-ARCHITECTURE.md) | 모듈 구성, 문서 세션, 노드 고정 라우팅 |
| 6 | [04-OT-DESIGN.md](./04-OT-DESIGN.md) | 블록 모델, 연산 7종, 변환 규칙 |
| 7 | [05-PROTOCOL.md](./05-PROTOCOL.md) | WebSocket 메시지, 재접속, 프레즌스 |
| 8 | [06-DATA-MODEL.md](./06-DATA-MODEL.md) | 연산 로그, 스냅샷, 테이블 14개 |
| 9 | [07-SECURITY.md](./07-SECURITY.md) | 토큰, 권한 모델, 연산 단위 인가 |
| 10 | [08-FEATURES.md](./08-FEATURES.md) | 버전 기록, 댓글, 멘션, 검색 |
| 11 | [09-API.md](./09-API.md) | 엔드포인트 46개 |
| 12 | [10-FRONTEND.md](./10-FRONTEND.md) | 편집기 어댑터, 상태 기계, 오프라인 저장 |
| 13 | [11-TEST-PLAN.md](./11-TEST-PLAN.md) | 속성 테스트, 장애 실험 7종, 부하 시나리오 5종 |
| 14 | [12-ROADMAP.md](./12-ROADMAP.md) | 8단계 24주 일정 |
| 16 | [13-RISKS.md](./13-RISKS.md) | 위험 6개, 미정 사항 |
| 15 | [adr/](./adr/README.md) | 결정 12개와 버린 대안 |

## 읽는 순서

1. 처음이면 [01-PLAN.md](./01-PLAN.md)의 1장과 [03-ARCHITECTURE.md](./03-ARCHITECTURE.md)를 먼저 읽는다.
2. 구현을 시작하면 [04-OT-DESIGN.md](./04-OT-DESIGN.md)와 [05-PROTOCOL.md](./05-PROTOCOL.md)가 기준이다.
3. 설계를 바꿀 때는 [adr/](./adr/README.md)에 결정을 남긴다.

## 아직 비어 있는 것

- 성공 기준 표(1.4)의 실측값. 6단계(측정과 개선)에서 채운다.
- `CHAOS.md`: 장애 실험 결과. 4단계와 6단계에서 만든다.
- 화면 시안.
