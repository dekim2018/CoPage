package com.copage.ot;

import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
import java.util.List;

/**
 * 같은 문서에서 출발한 두 연산을 서로에게 맞게 바꾼다.
 *
 * <p>서버가 문서마다 하나의 확정 순서를 만들기 때문에, 여기서 지켜야 할 성질은 하나다. 문서 S 에서 출발한 연산 a 와 b 에
 * 대해 "a 를 적용한 뒤 변환된 b 를 적용한 결과"와 "b 를 적용한 뒤 변환된 a 를 적용한 결과"가 같아야 한다(TP1).
 *
 * <p>둘 중 누가 나중에 확정되는지를 {@code aIsLater} 로 받는다. 같은 자리에 둘 다 글자를 넣거나 같은 값을 둘 다 바꾸는
 * 경우처럼 한쪽을 골라야 할 때, 나중에 확정되는 쪽의 값이 남고 먼저 확정되는 쪽의 글자가 앞에 온다.
 */
public final class Transformer {

    private Transformer() {
    }

    /** {@link #transform(List, List, boolean)} 의 결과. */
    public record Pair(List<Op> a, List<Op> b) {
    }

    /**
     * 연산 a 를, b 가 먼저 적용된 뒤의 문서에 맞게 바꾼다. 결과는 0개 이상의 연산이다.
     *
     * @throws TransformConflictException 두 연산을 변환으로 풀 수 없을 때
     */
    public static List<Op> transform(Op a, Op b, boolean aIsLater) {
        return switch (a) {
            case TextOp text -> transformText(text, b, aIsLater);
            case SplitBlock split -> transformSplit(split, b, aIsLater);
            case MergeBlock merge -> transformMerge(merge, b);
            case InsertBlock insert -> transformInsert(insert, b);
            case DeleteBlock delete -> transformDelete(delete, b, aIsLater);
            case MoveBlock move -> transformMove(move, b, aIsLater);
            case SetProps setProps -> transformSetProps(setProps, b, aIsLater);
        };
    }

    /**
     * 연산 묶음 두 개를 서로에게 맞게 바꾼다.
     *
     * @return a 는 b 뒤에 적용할 수 있게 바뀐 a 묶음, b 는 a 뒤에 적용할 수 있게 바뀐 b 묶음
     */
    public static Pair transform(List<Op> a, List<Op> b, boolean aIsLater) {
        if (a.isEmpty() || b.isEmpty()) {
            return new Pair(a, b);
        }
        if (a.size() == 1 && b.size() == 1) {
            Op first = a.get(0);
            Op second = b.get(0);
            return new Pair(transform(first, second, aIsLater), transform(second, first, !aIsLater));
        }
        if (a.size() > 1) {
            Pair head = transform(a.subList(0, 1), b, aIsLater);
            Pair rest = transform(a.subList(1, a.size()), head.b(), aIsLater);
            List<Op> merged = new ArrayList<>(head.a());
            merged.addAll(rest.a());
            return new Pair(merged, rest.b());
        }
        Pair head = transform(a, b.subList(0, 1), aIsLater);
        Pair rest = transform(head.a(), b.subList(1, b.size()), aIsLater);
        List<Op> merged = new ArrayList<>(head.b());
        merged.addAll(rest.b());
        return new Pair(rest.a(), merged);
    }

    private static List<Op> transformText(TextOp a, Op b, boolean aIsLater) {
        switch (b) {
            case TextOp other when other.blockId().equals(a.blockId()) -> {
                Delta delta = a.delta().transform(other.delta(), aIsLater);
                return delta.isNoop() ? List.of() : List.of(new TextOp(a.blockId(), delta));
            }
            case SplitBlock split when split.blockId().equals(a.blockId()) -> {
                // 블록이 나뉘었다. 편집을 앞 블록 몫과 새 블록 몫으로 가른다.
                Delta[] parts = a.delta().splitAt(split.offset());
                List<Op> out = new ArrayList<>(2);
                if (!parts[0].isNoop()) {
                    out.add(new TextOp(a.blockId(), parts[0]));
                }
                if (!parts[1].isNoop()) {
                    out.add(new TextOp(split.newBlockId(), parts[1]));
                }
                return out;
            }
            case MergeBlock merge when merge.blockId().equals(a.blockId()) -> {
                // 블록이 다른 블록 끝에 붙었다. 그 블록의 원래 길이만큼 뒤로 민다.
                return List.of(new TextOp(merge.intoId(), a.delta().shifted(merge.intoLength())));
            }
            default -> {
                return List.of(a);
            }
        }
    }

    private static List<Op> transformSplit(SplitBlock a, Op b, boolean aIsLater) {
        switch (b) {
            case TextOp text when text.blockId().equals(a.blockId()) -> {
                return List.of(a.at(a.blockId(), text.delta().transformPosition(a.offset())));
            }
            case SplitBlock other -> {
                requireDifferentNewBlock(a.newBlockId(), other.newBlockId());
                if (!other.blockId().equals(a.blockId()) || a.offset() < other.offset()) {
                    return List.of(a);
                }
                if (a.offset() > other.offset()) {
                    return List.of(a.at(other.newBlockId(), a.offset() - other.offset()));
                }
                // 두 사람이 같은 자리에서 Enter 를 눌렀다. 먼저 확정된 쪽의 새 블록이 뒤쪽 글을 갖고,
                // 나중 쪽의 새 블록은 빈 줄이 된다.
                if (aIsLater) {
                    return List.of(new InsertBlock(
                            a.newBlockId(), a.parentId(), a.pos(), a.type(), a.props(), RichText.EMPTY));
                }
                return List.of(a.at(other.newBlockId(), 0));
            }
            case MergeBlock merge -> {
                if (merge.blockId().equals(a.parentId())) {
                    throw conflict("새 블록이 놓일 부모가 다른 블록에 합쳐졌다");
                }
                if (merge.blockId().equals(a.blockId())) {
                    return List.of(a.at(merge.intoId(), a.offset() + merge.intoLength()));
                }
                return List.of(a);
            }
            case InsertBlock insert -> {
                requireDifferentNewBlock(a.newBlockId(), insert.blockId());
                return List.of(a);
            }
            case DeleteBlock delete when delete.blockId().equals(a.blockId()) ->
                    throw conflict("나누려는 블록이 삭제되었다");
            default -> {
                return List.of(a);
            }
        }
    }

    private static List<Op> transformMerge(MergeBlock a, Op b) {
        String source = a.blockId();
        String target = a.intoId();
        switch (b) {
            case TextOp text when text.blockId().equals(target) -> {
                return List.of(a.into(target, a.intoLength() + text.delta().lengthChange()));
            }
            case SplitBlock split -> {
                if (source.equals(split.parentId())) {
                    throw conflict("합쳐질 블록 아래에 새 블록이 생겼다");
                }
                if (split.blockId().equals(target)) {
                    // 붙일 대상이 나뉘었다. 뒤쪽 글을 가진 새 블록의 끝에 붙인다.
                    return List.of(a.into(split.newBlockId(), a.intoLength() - split.offset()));
                }
                return List.of(a);
            }
            case MergeBlock other -> {
                if (other.blockId().equals(source)) {
                    if (other.intoId().equals(target)) {
                        return List.of();
                    }
                    throw conflict("같은 블록을 서로 다른 블록에 합치려 했다");
                }
                if (other.blockId().equals(target)) {
                    if (other.intoId().equals(source)) {
                        throw conflict("두 블록을 서로에게 합치려 했다");
                    }
                    // 붙일 대상이 또 다른 블록에 합쳐졌다. 그 블록의 끝에 붙인다.
                    return List.of(a.into(other.intoId(), other.intoLength() + a.intoLength()));
                }
                if (other.intoId().equals(target)) {
                    throw conflict("서로 다른 두 블록을 같은 블록에 합치려 했다");
                }
                return List.of(a);
            }
            case InsertBlock insert when source.equals(insert.parentId()) ->
                    throw conflict("합쳐질 블록 아래에 새 블록이 생겼다");
            case DeleteBlock delete when delete.blockId().equals(source) || delete.blockId().equals(target) ->
                    throw conflict("합치려는 블록이 삭제되었다");
            case MoveBlock move when source.equals(move.parentId()) ->
                    throw conflict("합쳐질 블록 아래로 다른 블록이 옮겨졌다");
            default -> {
                return List.of(a);
            }
        }
    }

    private static List<Op> transformInsert(InsertBlock a, Op b) {
        switch (b) {
            case SplitBlock split -> requireDifferentNewBlock(a.blockId(), split.newBlockId());
            case InsertBlock insert -> requireDifferentNewBlock(a.blockId(), insert.blockId());
            case MergeBlock merge when merge.blockId().equals(a.parentId()) ->
                    throw conflict("새 블록이 놓일 부모가 다른 블록에 합쳐졌다");
            default -> {
            }
        }
        return List.of(a);
    }

    private static List<Op> transformDelete(DeleteBlock a, Op b, boolean aIsLater) {
        switch (b) {
            case DeleteBlock other when other.blockId().equals(a.blockId()) -> {
                return aIsLater ? List.of(a) : List.of();
            }
            case SplitBlock split when split.blockId().equals(a.blockId()) ->
                    throw conflict("삭제하려는 블록이 나뉘었다");
            case MergeBlock merge when merge.blockId().equals(a.blockId()) || merge.intoId().equals(a.blockId()) ->
                    throw conflict("삭제하려는 블록이 합쳐졌다");
            default -> {
                return List.of(a);
            }
        }
    }

    private static List<Op> transformMove(MoveBlock a, Op b, boolean aIsLater) {
        switch (b) {
            case MoveBlock other when other.blockId().equals(a.blockId()) -> {
                return aIsLater ? List.of(a) : List.of();
            }
            case MergeBlock merge when merge.blockId().equals(a.parentId()) ->
                    throw conflict("옮겨 갈 부모가 다른 블록에 합쳐졌다");
            default -> {
                return List.of(a);
            }
        }
    }

    private static List<Op> transformSetProps(SetProps a, Op b, boolean aIsLater) {
        if (b instanceof SetProps other && other.blockId().equals(a.blockId()) && other.key().equals(a.key())) {
            return aIsLater ? List.of(a) : List.of();
        }
        return List.of(a);
    }

    private static void requireDifferentNewBlock(String mine, String theirs) {
        if (mine.equals(theirs)) {
            throw conflict("두 연산이 같은 ID 로 새 블록을 만들려 했다");
        }
    }

    private static TransformConflictException conflict(String message) {
        return new TransformConflictException(message);
    }
}
