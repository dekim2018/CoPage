# ADR 0018. 모듈 경계는 ArchUnit 시험으로 지킨다

- 상태: 확정
- 결정일: 2026-11-24
- 관련 문서: [03-ARCHITECTURE.md](../03-ARCHITECTURE.md) 5.1, [02-TECH-STACK.md](../02-TECH-STACK.md), [review/DECISIONS.md](../review/DECISIONS.md) D-32

## 고른 것

ArchUnit 시험 `ModuleBoundaryTest` 하나에 네 규칙을 적는다.

- 모듈 사이에 순환이 없다.
- 다른 모듈은 `app` 과 `domain` 으로만 쓴다. `in`, `out`, `config` 는 그 모듈 안의 것이다.
- `domain` 과 `app` 은 `in`, `out`, `config` 를 모른다.
- `domain` 은 스프링을 모른다.

## 버린 대안

Spring Modulith(02-TECH-STACK 에 처음 적었던 것).

## 이유

- Modulith 는 모듈의 하위 패키지를 모두 내부로 본다. 지금 구조(다른 모듈이 `app` 의 포트와 서비스를 씀)에서는 패키지마다 NamedInterface 를 달아야 한다.
- 같은 규칙을 ArchUnit 은 그대로 적을 수 있고 Spring Boot 판과 엮이지 않는다.
- 규칙이 실제로 잡는지 확인했다. `workspace.app` 에서 `collab.out` 을 쓰는 임시 클래스를 넣으면 넷 중 셋이 실패한다.

## 감수하는 것

- Modulith 의 문서 생성과 모듈 단위 통합 시험은 쓰지 못한다.
- 모듈 목록은 패키지 이름으로 정해져, 새 모듈을 만들면 규칙이 저절로 덮지만 예외를 두려면 시험을 고쳐야 한다.

## 관계

ADR 0008(모듈형 단일 서버)의 경계를 지키는 방법이다.

## 다시 검토할 조건

모듈을 따로 배포하게 되거나 규칙에 예외가 늘면 이 문서를 고치고 상태를 "대체됨"으로 바꾼다.
