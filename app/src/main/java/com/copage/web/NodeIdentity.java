package com.copage.web;

/**
 * 이 서버 프로세스(노드)의 이름. 문서 소유 리스의 값과 노드 사이 알림의 보낸 쪽 표시에 쓴다 (docs/03-ARCHITECTURE.md 5.3, 5.5).
 * `COPAGE_NODE_ID` 로 정하고, 정하지 않으면 시작할 때 무작위로 만든다.
 */
public record NodeIdentity(String id) {
}
