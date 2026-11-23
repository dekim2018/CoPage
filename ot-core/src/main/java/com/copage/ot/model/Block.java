package com.copage.ot.model;

import com.copage.ot.text.Attrs;
import com.copage.ot.text.RichText;
import java.util.Map;

/**
 * 문서를 이루는 블록 하나. 바뀌지 않는 값이다.
 *
 * @param parentId 부모 블록. 루트 블록만 null 이다
 * @param pos 형제 사이 순서를 정하는 키. 문자열 순서로 비교하고, 같으면 블록 ID 순서를 따른다
 * @param deleted 삭제 표시. 표시된 블록과 그 아래는 화면에 보이지 않지만 문서에는 남는다
 */
public record Block(
        String id, String type, Map<String, Object> props, RichText text, String parentId, String pos, boolean deleted) {

    public Block {
        props = Attrs.copy(props);
    }

    public Block withText(RichText newText) {
        return new Block(id, type, props, newText, parentId, pos, deleted);
    }

    public Block withType(String newType) {
        return new Block(id, newType, props, text, parentId, pos, deleted);
    }

    public Block withProps(Map<String, Object> newProps) {
        return new Block(id, type, newProps, text, parentId, pos, deleted);
    }

    public Block withPlace(String newParentId, String newPos) {
        return new Block(id, type, props, text, newParentId, newPos, deleted);
    }

    public Block withDeleted(boolean newDeleted) {
        return new Block(id, type, props, text, parentId, pos, newDeleted);
    }
}
