package com.copage.workspace.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 트리의 페이지. id 가 곧 문서 ID 다.
 *
 * @param parentId 최상위 페이지면 null
 * @param pos 형제 사이 순서. 분수 인덱스 키를 문자열 순서로 비교한다
 * @param title 루트 블록에서 파생한다
 * @param restricted 상속을 끊었는지 (docs/07-SECURITY.md 9.4)
 */
public record Page(
        UUID id,
        UUID workspaceId,
        UUID parentId,
        String pos,
        String title,
        boolean restricted,
        UUID createdBy,
        Instant createdAt) {
}
