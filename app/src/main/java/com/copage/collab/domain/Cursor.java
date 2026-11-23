package com.copage.collab.domain;

/**
 * 커서와 선택 영역 (docs/05-PROTOCOL.md 7.6). 블록 ID 로 가리키므로 다른 블록이 바뀌어도 영향을 받지 않는다. 선택이 없으면
 * anchor 와 head 가 같다.
 */
public record Cursor(Position anchor, Position head) {

    /** 블록 안의 위치. offset 은 UTF-16 코드 단위다. */
    public record Position(String blockId, int offset) {
    }
}
