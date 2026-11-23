/**
 * 계정 모듈. 가입, 로그인, 토큰 발급과 회전, 내 정보 (docs/03-ARCHITECTURE.md 5.1, docs/07-SECURITY.md 9.1~9.2).
 *
 * <p>사용자는 PostgreSQL, Refresh 토큰과 Access 토큰 차단 목록과 로그인 실패 횟수는 Redis 에 둔다.
 */
package com.copage.identity;
