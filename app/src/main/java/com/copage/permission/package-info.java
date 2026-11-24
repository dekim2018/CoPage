/**
 * 권한 모듈. 페이지 권한 계산 (docs/03-ARCHITECTURE.md 5.1, docs/07-SECURITY.md 9.4).
 *
 * <p>workspace 모듈의 트리(pages, workspace_members)와 page_grants 를 SQL 로 읽는다. workspace 의 자바 코드는 참조하지 않는다.
 * 이메일로 사용자를 찾을 때만 identity 모듈의 UserDirectory 를 쓴다. 권한 부여와 회수, restricted 도 이 모듈이 한다.
 * workspace 와 collab 이 이 모듈에 권한을 묻는다. 권한 캐시는 두지 않는다. 캐시를 지우는 일관성 문제를 만들지 않으려는 것이고,
 * 부하 측정에서 필요가 보이면 그때 넣는다.
 */
package com.copage.permission;
