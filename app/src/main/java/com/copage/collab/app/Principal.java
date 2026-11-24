package com.copage.collab.app;

import java.util.UUID;

/**
 * 연결이 누구의 권한으로 열렸는지. 등급을 다시 계산하는 단위다.
 *
 * @param userId 로그인한 사용자, 또는 공유 링크로 들어온 비로그인 방문자에게 붙인 임시 ID
 * @param shareLinkId 공유 링크로 들어왔으면 그 링크 ID, 아니면 null. 링크로 들어온 연결의 등급은 링크가 살아 있는지에도 달려
 *     있다 (D-41)
 */
public record Principal(UUID userId, UUID shareLinkId) {
}
