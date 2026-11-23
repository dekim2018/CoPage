/**
 * 워크스페이스 모듈. 워크스페이스, 멤버, 페이지 트리 (docs/03-ARCHITECTURE.md 5.1, docs/06-DATA-MODEL.md 8.6).
 *
 * <p>페이지 ID 가 곧 문서 ID 다. 문서 내용은 collab 모듈이 다룬다. 이 모듈은 트리와 소속만 안다.
 *
 * <p>지금은 접근을 워크스페이스 역할로만 판단한다. 페이지 단위 권한(restricted, page_grants)은 permission 모듈이 맡는다
 * (docs/07-SECURITY.md 9.4).
 */
package com.copage.workspace;
