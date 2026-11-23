package com.copage.permission.domain;

import java.util.List;

/**
 * 한 사용자의 페이지 권한을 정하는 규칙 (docs/07-SECURITY.md 9.4). 저장소를 모르는 순수 함수다.
 *
 * <ol>
 *   <li>페이지에서 루트 방향으로 올라가며 restricted 가 켜진 첫 페이지를 찾는다. 이 페이지가 경계다
 *   <li>경계가 없으면 워크스페이스 역할의 기본 권한에서 시작한다. 경계가 있으면 없음에서 시작한다. OWNER 는 늘 FULL 이다
 *   <li>페이지부터 경계(없으면 루트)까지의 부여 중 가장 높은 등급을 더한다
 * </ol>
 *
 * <p>공유 링크(4번)는 아직 없다.
 */
public final class PermissionRules {

    /** 워크스페이스 역할. permission 은 workspace 모듈의 타입을 쓰지 않으므로 이름만 받는다. */
    public enum Role {
        OWNER,
        MEMBER,
        GUEST;

        PageLevel defaultLevel() {
            return switch (this) {
                case OWNER -> PageLevel.FULL;
                case MEMBER -> PageLevel.EDIT;
                case GUEST -> null;
            };
        }
    }

    /**
     * 조상 사슬의 한 칸.
     *
     * @param grant 이 페이지에서 이 사용자에게 준 등급. 없으면 null
     */
    public record Link(boolean restricted, PageLevel grant) {
    }

    private PermissionRules() {
    }

    /**
     * @param role 워크스페이스 역할. 속하지 않았으면 null
     * @param chain 페이지 자신부터 루트까지 차례로
     * @return 등급. 없으면 null
     */
    public static PageLevel level(Role role, List<Link> chain) {
        if (role == null) {
            return null;
        }
        if (role == Role.OWNER) {
            return PageLevel.FULL;
        }
        int boundary = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).restricted()) {
                boundary = i;
                break;
            }
        }
        PageLevel level = boundary < 0 ? role.defaultLevel() : null;
        int last = boundary < 0 ? chain.size() - 1 : boundary;
        for (int i = 0; i <= last; i++) {
            level = PageLevel.max(level, chain.get(i).grant());
        }
        return level;
    }

    /** 워크스페이스 최상위(부모 없음)에 페이지를 만들 때 쓰는 등급. 역할의 기본 권한이다. */
    public static PageLevel rootLevel(Role role) {
        return role == null ? null : role.defaultLevel();
    }
}
