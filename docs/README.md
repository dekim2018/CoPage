# 코페이지(가칭) 문서

실시간 공동 편집 문서 서비스의 기획과 설계 문서다. Java 25, Spring Boot 4.2, Spring Security 7.2, Vue 3을 쓴다.

문서 안에서 "6장", "8.4" 같은 표현은 아래 표의 장 번호를 가리킨다. 각 장은 설계와 함께 지금 구현된 상태를 적는다. 아직 만들지 않은 것은 "계획", "아직 없다" 로 표시한다.

| 장 | 파일 | 내용 |
| --- | --- | --- |
| 1 | [01-PLAN.md](./01-PLAN.md) | 목표, 확정된 결정, 성공 기준과 실측값 |
| 2 | [01-PLAN.md](./01-PLAN.md) | 기능 요구사항 40개, 비기능 5개 |
| 3 | [01-PLAN.md](./01-PLAN.md) | 화면 9개와 주요 흐름 3개 |
| 4 | [02-TECH-STACK.md](./02-TECH-STACK.md) | 백엔드, 프론트엔드, 인프라 선택과 이유 |
| 5 | [03-ARCHITECTURE.md](./03-ARCHITECTURE.md) | 모듈 구성, 문서 세션과 커밋 경로, 노드 고정 라우팅, 저장소 장애, 지표 |
| 6 | [04-OT-DESIGN.md](./04-OT-DESIGN.md) | 블록 모델, 연산 7종, 변환 규칙 |
| 7 | [05-PROTOCOL.md](./05-PROTOCOL.md) | WebSocket 메시지, 종료 코드, 재접속, 프레즌스, 한도 |
| 8 | [06-DATA-MODEL.md](./06-DATA-MODEL.md) | 연산 로그, 스냅샷, 테이블 14개(7개 구현)와 마이그레이션 |
| 9 | [07-SECURITY.md](./07-SECURITY.md) | 토큰, 권한 모델, 연산 단위 인가, 저장소 장애 정책 |
| 10 | [08-FEATURES.md](./08-FEATURES.md) | 버전 기록, 댓글, 멘션, 검색 (아직 없다) |
| 11 | [09-API.md](./09-API.md) | 엔드포인트 46개(21개 구현) |
| 12 | [10-FRONTEND.md](./10-FRONTEND.md) | 편집기 어댑터, 상태 기계, 보내기 간격과 나누기, 오프라인 저장 |
| 13 | [11-TEST-PLAN.md](./11-TEST-PLAN.md) | 시험 목록과 돌리는 법, 장애 실험, 부하 시나리오, 지표 |
| 14 | [12-ROADMAP.md](./12-ROADMAP.md) | 8단계 24주 일정과 지금 상태 |
| 16 | [13-RISKS.md](./13-RISKS.md) | 위험, 실험이 드러낸 위험, 미정 사항 |
| 15 | [adr/](./adr/README.md) | 결정 18개와 버린 대안 |
|  | [CHAOS.md](./CHAOS.md) | 장애 실험 결과 |

## 진단과 개선 기록

아키텍처 진단(2026-11-23)과 그 처리 기록이다.

| 파일 | 내용 |
| --- | --- |
| [review/ARCHITECTURE-REVIEW.md](./review/ARCHITECTURE-REVIEW.md) | 아키텍처 진단 |
| [review/SECURITY-REVIEW.md](./review/SECURITY-REVIEW.md) | 보안 진단 |
| [review/DATA-CONSISTENCY-REVIEW.md](./review/DATA-CONSISTENCY-REVIEW.md) | 데이터 정합성 진단 |
| [review/PERFORMANCE-REVIEW.md](./review/PERFORMANCE-REVIEW.md) | 성능 진단과 부하 측정 |
| [review/IMPLEMENTATION-REPORT.md](./review/IMPLEMENTATION-REPORT.md) | 개선 작업 보고 |
| [review/IMPROVEMENT-ROADMAP.md](./review/IMPROVEMENT-ROADMAP.md) | 남은 개선 과제 |
| [review/DECISIONS.md](./review/DECISIONS.md) | 개선 작업 중 내린 결정 D-01~D-38. 문서의 "D-NN" 은 이 파일을 가리킨다 |

## 읽는 순서

1. 처음이면 [01-PLAN.md](./01-PLAN.md)의 1장과 [03-ARCHITECTURE.md](./03-ARCHITECTURE.md)를 먼저 읽는다.
2. 구현을 시작하면 [04-OT-DESIGN.md](./04-OT-DESIGN.md)와 [05-PROTOCOL.md](./05-PROTOCOL.md)가 기준이다.
3. 설계를 바꿀 때는 [adr/](./adr/README.md)에 결정을 남긴다.

## 아직 비어 있는 것

- 성공 기준 표(1.4)의 실측값 가운데 다중 노드(노드 넘김의 재개 시간)와 4GB 서버에서 잰 값. 4단계와 7단계에서 채운다.
- `CHAOS.md` 의 다중 노드 실험(노드 일시 정지, 노드 추가).
- 화면 시안.
