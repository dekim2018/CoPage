package com.copage.workspace.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 목록에 보일 제목 다듬기 (D-21). */
class TitleNormalizationTest {

    @Test
    void 앞뒤_공백을_빼고_제어_문자는_공백으로_바꾸며_안쪽_연속_공백은_둔다() {
        assertEquals("주간  회의록", WorkspaceService.normalizeTitle("  주간  회의록 \t"));
        assertEquals("첫 줄 둘째 줄", WorkspaceService.normalizeTitle("첫 줄\n둘째 줄"));
        assertEquals("a b", WorkspaceService.normalizeTitle("a\u0007b"));
    }

    @Test
    void 이백_코드_단위에서_자르되_서로게이트_쌍은_가르지_않는다() {
        assertEquals("가".repeat(200), WorkspaceService.normalizeTitle("가".repeat(201)));
        assertEquals("가".repeat(200), WorkspaceService.normalizeTitle("가".repeat(200)));
        // 200번째와 201번째 코드 단위가 이모지 하나다. 반쪽을 남기지 않고 199에서 자른다.
        assertEquals("가".repeat(199), WorkspaceService.normalizeTitle("가".repeat(199) + "😀"));
        assertEquals("가".repeat(198) + "😀", WorkspaceService.normalizeTitle("가".repeat(198) + "😀끝"));
    }
}
