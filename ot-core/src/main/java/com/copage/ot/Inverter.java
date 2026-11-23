package com.copage.ot;

import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.ot.text.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * 연산을 되돌리는 연산(역연산)을 만든다. 되돌리기(undo)에 쓴다.
 *
 * <p>역연산을 적용하면 화면에 보이는 내용이 원래대로 돌아온다. 새로 만든 블록은 없애지 않고 삭제 표시만 하므로, 숨은
 * 블록까지 포함한 문서 전체가 완전히 같아지지는 않는다.
 */
public final class Inverter {

    private Inverter() {
    }

    /** before 문서에 op 를 적용했을 때, 그 결과를 되돌리는 연산들을 돌려준다. */
    public static List<Op> invert(Op op, Document before) {
        return switch (op) {
            case TextOp text -> {
                Delta inverse = text.delta().invert(before.get(text.blockId()).text());
                yield inverse.isNoop() ? List.of() : List.of(new TextOp(text.blockId(), inverse));
            }
            case SplitBlock split -> List.of(new MergeBlock(split.newBlockId(), split.blockId(), split.offset()));
            case MergeBlock merge -> invertMerge(merge, before);
            case InsertBlock insert -> List.of(new DeleteBlock(insert.blockId(), true));
            case DeleteBlock delete ->
                    List.of(new DeleteBlock(delete.blockId(), before.get(delete.blockId()).deleted()));
            case MoveBlock move -> {
                Block block = before.get(move.blockId());
                yield List.of(new MoveBlock(block.id(), block.parentId(), block.pos()));
            }
            case SetProps setProps -> {
                Block block = before.get(setProps.blockId());
                Object previous = Op.TYPE_KEY.equals(setProps.key())
                        ? block.type()
                        : block.props().get(setProps.key());
                yield List.of(new SetProps(block.id(), setProps.key(), previous));
            }
        };
    }

    /** 묶음 전체의 역연산. 뒤에서부터 하나씩 되돌린다. */
    public static List<Op> invert(List<Op> ops, Document before) {
        List<List<Op>> inverses = new ArrayList<>();
        Document current = before;
        for (Op op : ops) {
            inverses.add(invert(op, current));
            current = current.apply(op);
        }
        List<Op> out = new ArrayList<>();
        for (int i = inverses.size() - 1; i >= 0; i--) {
            out.addAll(inverses.get(i));
        }
        return out;
    }

    private static List<Op> invertMerge(MergeBlock merge, Document before) {
        Block source = before.get(merge.blockId());
        List<Op> out = new ArrayList<>();
        if (!source.text().isEmpty()) {
            Delta removeTail = Delta.builder()
                    .retain(merge.intoLength())
                    .delete(source.text().length())
                    .build();
            out.add(new TextOp(merge.intoId(), removeTail));
        }
        out.add(new DeleteBlock(source.id(), false));
        if (!source.text().isEmpty()) {
            Delta.Builder restore = Delta.builder();
            for (Segment segment : source.text().segments()) {
                restore.insert(segment.text(), segment.attrs());
            }
            out.add(new TextOp(source.id(), restore.build()));
        }
        return out;
    }
}
