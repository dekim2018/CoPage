package com.copage.permission.domain;

import static com.copage.permission.domain.PageLevel.COMMENT;
import static com.copage.permission.domain.PageLevel.EDIT;
import static com.copage.permission.domain.PageLevel.FULL;
import static com.copage.permission.domain.PageLevel.VIEW;
import static com.copage.permission.domain.PermissionRules.Role.GUEST;
import static com.copage.permission.domain.PermissionRules.Role.MEMBER;
import static com.copage.permission.domain.PermissionRules.Role.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.copage.permission.domain.PermissionRules.Link;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/07-SECURITY.md 9.4 의 계산 순서를 사례로 고정한다. 사슬은 페이지 자신부터 루트까지다. */
class PermissionRulesTest {

    private static Link open() {
        return new Link(false, null);
    }

    private static Link open(PageLevel grant) {
        return new Link(false, grant);
    }

    private static Link restricted() {
        return new Link(true, null);
    }

    private static Link restricted(PageLevel grant) {
        return new Link(true, grant);
    }

    @Test
    void 경계가_없으면_역할의_기본_권한이다() {
        List<Link> chain = List.of(open(), open());

        assertEquals(FULL, PermissionRules.level(OWNER, chain));
        assertEquals(EDIT, PermissionRules.level(MEMBER, chain));
        assertNull(PermissionRules.level(GUEST, chain));
        assertNull(PermissionRules.level(null, chain), "워크스페이스에 속하지 않으면 없음");
    }

    @Test
    void 부여는_기본_권한보다_높을_때만_올린다() {
        assertEquals(FULL, PermissionRules.level(MEMBER, List.of(open(FULL))));
        assertEquals(EDIT, PermissionRules.level(MEMBER, List.of(open(VIEW))), "낮은 부여가 기본 권한을 깎지 않는다");
        assertEquals(COMMENT, PermissionRules.level(GUEST, List.of(open(COMMENT))));
    }

    @Test
    void 조상에_준_권한은_하위로_이어지고_가장_높은_것을_쓴다() {
        assertEquals(VIEW, PermissionRules.level(GUEST, List.of(open(), open(), open(VIEW))));
        assertEquals(EDIT, PermissionRules.level(GUEST, List.of(open(VIEW), open(EDIT), open(COMMENT))));
    }

    @Test
    void 경계가_있으면_기본_권한을_버리고_경계까지의_부여만_본다() {
        // 페이지 자신이 경계
        assertNull(PermissionRules.level(MEMBER, List.of(restricted(), open())));
        assertEquals(COMMENT, PermissionRules.level(MEMBER, List.of(restricted(COMMENT), open())));
        // 부모가 경계. 경계 위(루트 쪽)의 부여는 내려오지 않는다.
        assertNull(PermissionRules.level(MEMBER, List.of(open(), restricted(), open(FULL))));
        assertEquals(VIEW, PermissionRules.level(GUEST, List.of(open(), restricted(VIEW), open(FULL))));
        // 경계 아래의 부여는 쓴다.
        assertEquals(EDIT, PermissionRules.level(MEMBER, List.of(open(EDIT), restricted(), open())));
    }

    @Test
    void 가장_가까운_경계만_본다() {
        // 자신과 가까운 경계 사이의 부여만 센다. 더 먼 경계와 그 사이의 부여는 보지 않는다.
        assertEquals(VIEW, PermissionRules.level(GUEST, List.of(open(), restricted(VIEW), open(FULL), restricted(FULL))));
        assertNull(PermissionRules.level(GUEST, List.of(restricted(), open(EDIT), restricted(EDIT))));
    }

    @Test
    void OWNER_는_경계가_있어도_늘_전체_권한이다() {
        assertEquals(FULL, PermissionRules.level(OWNER, List.of(restricted(), open())));
    }

    @Test
    void 최상위에_만들_때는_역할의_기본_권한이다() {
        assertEquals(FULL, PermissionRules.rootLevel(OWNER));
        assertEquals(EDIT, PermissionRules.rootLevel(MEMBER));
        assertNull(PermissionRules.rootLevel(GUEST));
        assertNull(PermissionRules.rootLevel(null));
    }
}
