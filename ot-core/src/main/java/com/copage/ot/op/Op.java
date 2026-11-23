package com.copage.ot.op;

import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.Map;

/**
 * 문서를 바꾸는 연산 7종.
 *
 * <p>위치(offset)로 글자를 가리키는 연산은 {@link TextOp}, {@link SplitBlock}, {@link MergeBlock} 셋이고, 나머지는 블록 ID
 * 만 쓴다. sealed interface 라서 연산 종류를 추가하면 변환과 적용의 switch 가 컴파일되지 않는다.
 */
public sealed interface Op
        permits Op.TextOp, Op.SplitBlock, Op.MergeBlock, Op.InsertBlock, Op.DeleteBlock, Op.MoveBlock, Op.SetProps {

    /** 블록 종류를 바꿀 때 {@link SetProps} 에 쓰는 키. */
    String TYPE_KEY = "type";

    /** 이 연산이 대상으로 삼는 블록. */
    String blockId();

    /** 블록 안의 글을 고친다. 삭제 표시된 블록에도 적용된다. */
    record TextOp(String blockId, Delta delta) implements Op {
    }

    /**
     * 블록을 offset 에서 둘로 나눈다. 뒤쪽 글이 새 블록으로 간다.
     *
     * <p>새 블록의 부모, 순서, 종류, 속성을 전부 연산에 적는다. 나누려던 블록이 그 사이 다른 블록에 합쳐져도 새 블록이
     * 어디에 어떤 모습으로 생길지가 달라지지 않게 하기 위해서다.
     */
    record SplitBlock(
            String blockId,
            int offset,
            String newBlockId,
            String parentId,
            String pos,
            String type,
            Map<String, Object> props)
            implements Op {
        public SplitBlock {
            props = Attrs.copy(props);
        }

        public SplitBlock at(String targetBlockId, int targetOffset) {
            return new SplitBlock(targetBlockId, targetOffset, newBlockId, parentId, pos, type, props);
        }
    }

    /**
     * 블록의 글을 intoId 블록 끝에 붙이고 블록을 비운 뒤 삭제 표시한다.
     *
     * @param intoLength 합치기 직전 intoId 블록의 글 길이. 이 블록을 고치던 다른 사람의 텍스트 연산을 옮기는 데 쓴다
     */
    record MergeBlock(String blockId, String intoId, int intoLength) implements Op {
        public MergeBlock into(String targetId, int targetLength) {
            return new MergeBlock(blockId, targetId, targetLength);
        }
    }

    /** 새 블록을 넣는다. blockId 는 클라이언트가 만든 새 ID 다. */
    record InsertBlock(
            String blockId, String parentId, String pos, String type, Map<String, Object> props, RichText text)
            implements Op {
        public InsertBlock {
            props = Attrs.copy(props);
            text = text == null ? RichText.EMPTY : text;
        }
    }

    /** 블록에 삭제 표시를 하거나(deleted = true) 표시를 지워 되살린다(deleted = false). 아래 블록은 함께 숨겨진다. */
    record DeleteBlock(String blockId, boolean deleted) implements Op {
    }

    /** 블록의 부모나 형제 사이 순서를 바꾼다. 들여쓰기와 내어쓰기도 이 연산이다. */
    record MoveBlock(String blockId, String parentId, String pos) implements Op {
    }

    /** 블록 속성 하나를 바꾼다. value 가 null 이면 속성을 지운다. key 가 {@link #TYPE_KEY} 면 블록 종류를 바꾼다. */
    record SetProps(String blockId, String key, Object value) implements Op {
    }
}
