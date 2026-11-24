/**
 * 협업 모듈. 문서 세션, WebSocket 규약, 연산 로그, 1회용 티켓을 다룬다 (docs/03-ARCHITECTURE.md 5.1).
 *
 * <ul>
 *   <li>{@code domain}: 규약 메시지, 권한 등급, 확정된 묶음 같은 값
 *   <li>{@code app}: 문서 세션과 그 규칙. 저장소와 티켓 저장소는 포트(인터페이스)로만 안다
 *   <li>{@code in}: WebSocket 핸들러, 핸드셰이크 검사, JSON 변환, 티켓 발급(권한을 permission 모듈에 묻는다)
 *   <li>{@code out}: 저장소 구현(PostgreSQL 연산 로그와 스냅샷, Redis 티켓)과 지표(Micrometer)
 *   <li>{@code config}: 스프링 설정과 한도 값
 * </ul>
 */
package com.copage.collab;
