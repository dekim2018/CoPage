package com.copage.ot;

import com.copage.ot.op.Op;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import java.util.ArrayList;
import java.util.List;

/** 연산 묶음을 다루는 도우미. */
public final class Changes {

    private Changes() {
    }

    /**
     * 묶음 뒤에 연산을 이어 붙인다. 같은 블록의 텍스트 편집이 맞닿으면 하나로 합성한다.
     *
     * <p>"안녕하세요"를 한 글자씩 치면 텍스트 연산 5개가 생기지만, 아직 보내지 않은 묶음 안에서는 삽입 하나로 줄어든다.
     */
    public static List<Op> append(List<Op> change, List<Op> more) {
        List<Op> out = new ArrayList<>(change);
        for (Op op : more) {
            int last = out.size() - 1;
            if (op instanceof TextOp next
                    && last >= 0
                    && out.get(last) instanceof TextOp previous
                    && previous.blockId().equals(next.blockId())) {
                Delta composed = previous.delta().compose(next.delta());
                if (composed.isNoop()) {
                    out.remove(last);
                } else {
                    out.set(last, new TextOp(next.blockId(), composed));
                }
            } else {
                out.add(op);
            }
        }
        return out;
    }
}
