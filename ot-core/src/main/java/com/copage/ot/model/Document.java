package com.copage.ot.model;

import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.MoveBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 페이지 하나의 내용. 블록 트리다.
 *
 * <p>블록은 ID 로 찾고, 트리 모양은 각 블록의 부모 ID 와 순서 키로 표현한다. 자식 목록을 따로 들고 있지 않아서 블록을
 * 옮기거나 끼워 넣어도 그 블록 하나만 바뀐다.
 *
 * <p>{@link #apply(List)} 는 원본을 건드리지 않고 새 문서를 돌려준다. 묶음 안의 연산 하나라도 실패하면 예외가 나고 원본은
 * 그대로다. 묶음이 전부 적용되거나 전부 거부된다는 규칙이 여기서 지켜진다.
 */
public final class Document {

    public static final String ROOT_TYPE = "page";

    private static final Comparator<Block> SIBLING_ORDER =
            Comparator.comparing(Block::pos).thenComparing(Block::id);

    private final String rootId;
    private final Map<String, Block> blocks;

    private Document(String rootId, Map<String, Block> blocks) {
        this.rootId = rootId;
        this.blocks = blocks;
    }

    /** 제목이 빈 루트 블록 하나만 있는 새 문서. */
    public static Document create(String rootId) {
        Map<String, Block> blocks = new HashMap<>();
        blocks.put(rootId, new Block(rootId, ROOT_TYPE, Attrs.NONE, RichText.EMPTY, null, "", false));
        return new Document(rootId, blocks);
    }

    /**
     * 스냅샷처럼 이미 있는 블록들로 문서를 만든다. 루트 블록(부모가 없는 블록)은 rootId 하나뿐이어야 하고, 다른 블록의 부모는
     * 모두 문서 안에 있어야 한다.
     *
     * @throws IllegalArgumentException 위 조건을 어길 때
     */
    public static Document of(String rootId, Iterable<Block> blocks) {
        Map<String, Block> map = new HashMap<>();
        for (Block block : blocks) {
            if (map.put(block.id(), block) != null) {
                throw new IllegalArgumentException("블록 " + block.id() + " 이 두 번 있다");
            }
        }
        Block root = map.get(rootId);
        if (root == null || root.parentId() != null) {
            throw new IllegalArgumentException("루트 블록 " + rootId + " 이 없다");
        }
        for (Block block : map.values()) {
            if (!block.id().equals(rootId) && (block.parentId() == null || !map.containsKey(block.parentId()))) {
                throw new IllegalArgumentException("블록 " + block.id() + " 의 부모가 문서에 없다");
            }
        }
        return new Document(rootId, map);
    }

    public String rootId() {
        return rootId;
    }

    public Block get(String blockId) {
        return blocks.get(blockId);
    }

    public boolean contains(String blockId) {
        return blocks.containsKey(blockId);
    }

    public int blockCount() {
        return blocks.size();
    }

    public Iterable<Block> blocks() {
        return blocks.values();
    }

    /** 삭제 표시된 것까지 포함한 자식 블록을 화면 순서대로 돌려준다. */
    public List<Block> children(String parentId) {
        List<Block> children = new ArrayList<>();
        for (Block block : blocks.values()) {
            if (parentId.equals(block.parentId())) {
                children.add(block);
            }
        }
        children.sort(SIBLING_ORDER);
        return children;
    }

    /** 블록 자신과 모든 조상에 삭제 표시가 없으면 true. */
    public boolean isVisible(String blockId) {
        Block block = blocks.get(blockId);
        while (block != null) {
            if (block.deleted()) {
                return false;
            }
            if (block.parentId() == null) {
                return true;
            }
            block = blocks.get(block.parentId());
        }
        return false;
    }

    /** candidateId 가 ancestorId 와 같거나 그 아래에 있으면 true. */
    public boolean isSelfOrDescendant(String candidateId, String ancestorId) {
        Block block = blocks.get(candidateId);
        while (block != null) {
            if (block.id().equals(ancestorId)) {
                return true;
            }
            block = block.parentId() == null ? null : blocks.get(block.parentId());
        }
        return false;
    }

    /** 연산 묶음을 적용한 새 문서를 돌려준다. 하나라도 적용할 수 없으면 {@link InvalidOperationException} 을 던진다. */
    public Document apply(List<Op> ops) {
        if (ops.isEmpty()) {
            return this;
        }
        Document next = new Document(rootId, new HashMap<>(blocks));
        for (Op op : ops) {
            next.applyInPlace(op);
        }
        return next;
    }

    public Document apply(Op op) {
        return apply(List.of(op));
    }

    private void applyInPlace(Op op) {
        switch (op) {
            case TextOp text -> applyText(text);
            case SplitBlock split -> applySplit(split);
            case MergeBlock merge -> applyMerge(merge);
            case InsertBlock insert -> applyInsert(insert);
            case DeleteBlock delete -> {
                Block block = requireNonRoot(delete.blockId(), "삭제");
                blocks.put(block.id(), block.withDeleted(delete.deleted()));
            }
            case MoveBlock move -> applyMove(move);
            case SetProps setProps -> applySetProps(setProps);
        }
    }

    private void applyText(TextOp op) {
        Block block = require(op.blockId());
        try {
            blocks.put(block.id(), block.withText(op.delta().apply(block.text())));
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("블록 " + block.id() + " 의 텍스트 편집 실패: " + e.getMessage());
        }
    }

    private void applySplit(SplitBlock op) {
        Block block = requireNonRoot(op.blockId(), "나누기");
        if (block.deleted()) {
            throw new InvalidOperationException("삭제된 블록 " + block.id() + " 은 나눌 수 없다");
        }
        RichText text = block.text();
        if (op.offset() < 0 || op.offset() > text.length()) {
            throw new InvalidOperationException("나눌 위치 " + op.offset() + " 이 글 길이 " + text.length() + " 를 벗어난다");
        }
        if (text.splitsSurrogatePair(op.offset())) {
            throw new InvalidOperationException("코드 단위 2개짜리 글자의 가운데는 나눌 수 없다");
        }
        requireNewBlock(op.newBlockId(), op.parentId(), op.pos(), op.type());
        blocks.put(block.id(), block.withText(text.slice(0, op.offset())));
        blocks.put(
                op.newBlockId(),
                new Block(
                        op.newBlockId(),
                        op.type(),
                        op.props(),
                        text.slice(op.offset(), text.length()),
                        op.parentId(),
                        op.pos(),
                        false));
    }

    private void applyMerge(MergeBlock op) {
        if (op.blockId().equals(op.intoId())) {
            throw new InvalidOperationException("블록을 자기 자신에 합칠 수 없다");
        }
        Block source = requireNonRoot(op.blockId(), "합치기");
        Block target = requireNonRoot(op.intoId(), "합치기");
        if (source.deleted() || target.deleted()) {
            throw new InvalidOperationException("삭제된 블록은 합칠 수 없다");
        }
        if (target.text().length() != op.intoLength()) {
            throw new InvalidOperationException(
                    "합칠 대상의 글 길이가 다르다: 연산 " + op.intoLength() + ", 실제 " + target.text().length());
        }
        for (Block block : blocks.values()) {
            if (source.id().equals(block.parentId()) && !block.deleted()) {
                throw new InvalidOperationException("자식 블록이 남아 있는 블록 " + source.id() + " 은 합칠 수 없다");
            }
        }
        blocks.put(target.id(), target.withText(target.text().concat(source.text())));
        blocks.put(source.id(), source.withText(RichText.EMPTY).withDeleted(true));
    }

    private void applyInsert(InsertBlock op) {
        requireNewBlock(op.blockId(), op.parentId(), op.pos(), op.type());
        blocks.put(
                op.blockId(),
                new Block(op.blockId(), op.type(), op.props(), op.text(), op.parentId(), op.pos(), false));
    }

    private void applyMove(MoveBlock op) {
        Block block = requireNonRoot(op.blockId(), "이동");
        require(op.parentId());
        if (op.pos() == null) {
            throw new InvalidOperationException("순서 키가 없다");
        }
        if (isSelfOrDescendant(op.parentId(), block.id())) {
            throw new InvalidOperationException("블록 " + block.id() + " 을 자기 자신이나 자기 아래로 옮길 수 없다");
        }
        blocks.put(block.id(), block.withPlace(op.parentId(), op.pos()));
    }

    private void applySetProps(SetProps op) {
        Block block = require(op.blockId());
        if (op.key() == null || op.key().isEmpty()) {
            throw new InvalidOperationException("속성 이름이 없다");
        }
        if (Op.TYPE_KEY.equals(op.key())) {
            if (block.parentId() == null) {
                throw new InvalidOperationException("루트 블록의 종류는 바꿀 수 없다");
            }
            if (!(op.value() instanceof String type) || type.isEmpty() || ROOT_TYPE.equals(type)) {
                throw new InvalidOperationException("블록 종류가 올바르지 않다: " + op.value());
            }
            blocks.put(block.id(), block.withType(type));
            return;
        }
        Map<String, Object> props = new TreeMap<>(block.props());
        if (op.value() == null) {
            props.remove(op.key());
        } else {
            props.put(op.key(), op.value());
        }
        blocks.put(block.id(), block.withProps(props));
    }

    private Block require(String blockId) {
        Block block = blockId == null ? null : blocks.get(blockId);
        if (block == null) {
            throw new InvalidOperationException("블록 " + blockId + " 이 없다");
        }
        return block;
    }

    private Block requireNonRoot(String blockId, String action) {
        Block block = require(blockId);
        if (block.parentId() == null) {
            throw new InvalidOperationException("루트 블록에는 " + action + " 연산을 쓸 수 없다");
        }
        return block;
    }

    private void requireNewBlock(String newBlockId, String parentId, String pos, String type) {
        if (newBlockId == null || newBlockId.isEmpty()) {
            throw new InvalidOperationException("새 블록 ID 가 없다");
        }
        if (blocks.containsKey(newBlockId)) {
            throw new InvalidOperationException("블록 " + newBlockId + " 이 이미 있다");
        }
        require(parentId);
        if (pos == null) {
            throw new InvalidOperationException("순서 키가 없다");
        }
        if (type == null || type.isEmpty() || ROOT_TYPE.equals(type)) {
            throw new InvalidOperationException("블록 종류가 올바르지 않다: " + type);
        }
    }

    /**
     * 화면에 보이는 내용만 들여쓰기한 글로 펼친다. 삭제 표시된 블록과 그 아래는 빠진다.
     *
     * <p>되돌리기 검증처럼 "사용자가 보는 내용이 같은가"를 비교할 때 쓴다.
     */
    public String renderVisible() {
        StringBuilder out = new StringBuilder();
        render(blocks.get(rootId), 0, out);
        return out.toString();
    }

    private void render(Block block, int depth, StringBuilder out) {
        if (block.deleted()) {
            return;
        }
        out.append("  ".repeat(depth))
                .append(block.id())
                .append(' ')
                .append(block.type())
                .append(block.props().isEmpty() ? "" : block.props().toString())
                .append(' ')
                .append(block.text())
                .append('\n');
        for (Block child : children(block.id())) {
            render(child, depth + 1, out);
        }
    }

    /** 삭제 표시된 블록까지 포함해 모든 블록이 같으면 같은 문서다. 수렴 검증은 이 비교를 쓴다. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Document other && rootId.equals(other.rootId) && blocks.equals(other.blocks);
    }

    @Override
    public int hashCode() {
        return blocks.hashCode();
    }

    @Override
    public String toString() {
        return renderVisible();
    }
}
