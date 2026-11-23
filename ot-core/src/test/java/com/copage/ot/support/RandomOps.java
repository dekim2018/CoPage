package com.copage.ot.support;

import com.copage.ot.FractionalIndex;
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
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 테스트용 무작위 문서와 연산 생성기.
 *
 * <p>항상 주어진 문서에 적용할 수 있는 연산만 만든다. 시드가 같으면 같은 연산이 나오므로 실패한 사례를 그대로 다시 돌릴
 * 수 있다. 순서 키와 서식 값은 일부러 적은 종류에서 고른다. 그래야 "같은 자리", "같은 키" 충돌이 자주 생긴다.
 */
public final class RandomOps {

    private static final String[] TYPES = {"paragraph", "heading", "todo", "quote"};
    private static final String[] POS_KEYS = {"A", "M", "V", "a", "m", "z"};
    private static final String ALPHABET = "abcde가나다라 ";

    private final Random random;
    private final String idPrefix;
    private int counter;

    public RandomOps(Random random, String idPrefix) {
        this.random = random;
        this.idPrefix = idPrefix;
    }

    /** 루트 아래에 블록이 blockCount 개쯤 있는 문서를 만든다. */
    public Document randomDocument(int blockCount) {
        Document doc = Document.create("root");
        doc = doc.apply(new TextOp("root", Delta.builder().insert(randomString(4)).build()));
        for (int i = 0; i < blockCount; i++) {
            doc = doc.apply(insertBlock(doc));
        }
        return doc;
    }

    /** doc 에 차례로 적용할 수 있는 연산 묶음을 만든다. */
    public List<Op> randomChange(Document doc, int maxOps) {
        List<Op> ops = new ArrayList<>();
        Document current = doc;
        int count = 1 + random.nextInt(maxOps);
        for (int i = 0; i < count; i++) {
            Op op = randomOp(current);
            ops.add(op);
            current = current.apply(op);
        }
        return ops;
    }

    /** doc 에 적용할 수 있는 연산 하나를 만든다. */
    public Op randomOp(Document doc) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int roll = random.nextInt(100);
            Op op;
            if (roll < 40) {
                op = text(doc);
            } else if (roll < 54) {
                op = split(doc);
            } else if (roll < 66) {
                op = merge(doc);
            } else if (roll < 74) {
                op = insertBlock(doc);
            } else if (roll < 82) {
                op = delete(doc);
            } else if (roll < 91) {
                op = move(doc);
            } else {
                op = setProps(doc);
            }
            if (op != null) {
                return op;
            }
        }
        return insertBlock(doc);
    }

    private Op text(Document doc) {
        Block block = pick(all(doc));
        int length = block.text().length();
        Delta.Builder delta = Delta.builder();
        int cursor = 0;
        int edits = 1 + random.nextInt(2);
        for (int i = 0; i < edits; i++) {
            int skip = random.nextInt(length - cursor + 1);
            delta.retain(skip);
            cursor += skip;
            int kind = random.nextInt(10);
            if (kind < 5) {
                delta.insert(randomString(3), random.nextInt(4) == 0 ? Attrs.of("bold", true) : Attrs.NONE);
            } else if (kind < 8 && cursor < length) {
                int count = 1 + random.nextInt(length - cursor);
                delta.delete(count);
                cursor += count;
            } else if (cursor < length) {
                int count = 1 + random.nextInt(length - cursor);
                delta.retain(count, randomAttrChange());
                cursor += count;
            } else {
                delta.insert(randomString(2));
            }
        }
        Delta built = delta.build();
        return built.isNoop() ? null : new TextOp(block.id(), built);
    }

    private Op split(Document doc) {
        List<Block> candidates = all(doc).stream()
                .filter(b -> b.parentId() != null && !b.deleted())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        Block block = pick(candidates);
        String parent = random.nextInt(5) == 0 ? pick(all(doc)).id() : block.parentId();
        return new SplitBlock(
                block.id(),
                random.nextInt(block.text().length() + 1),
                newId(),
                parent,
                randomPos(),
                random.nextInt(3) == 0 ? pickType() : block.type(),
                random.nextInt(4) == 0 ? Attrs.of("checked", true) : block.props());
    }

    private Op merge(Document doc) {
        List<Block> live = all(doc).stream()
                .filter(b -> b.parentId() != null && !b.deleted())
                .toList();
        List<Block> sources = live.stream()
                .filter(b -> doc.children(b.id()).stream().allMatch(Block::deleted))
                .toList();
        if (sources.isEmpty() || live.size() < 2) {
            return null;
        }
        Block source = pick(sources);
        List<Block> targets = live.stream().filter(b -> !b.id().equals(source.id())).toList();
        Block target = pick(targets);
        return new MergeBlock(source.id(), target.id(), target.text().length());
    }

    private Op insertBlock(Document doc) {
        Block parent = random.nextInt(3) == 0 ? pick(all(doc)) : doc.get(doc.rootId());
        return new InsertBlock(
                newId(),
                parent.id(),
                randomPos(),
                pickType(),
                random.nextInt(4) == 0 ? Attrs.of("level", 2) : Attrs.NONE,
                RichText.of(randomString(5)));
    }

    private Op delete(Document doc) {
        List<Block> candidates =
                all(doc).stream().filter(b -> b.parentId() != null).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        return new DeleteBlock(pick(candidates).id(), random.nextInt(5) != 0);
    }

    private Op move(Document doc) {
        List<Block> candidates =
                all(doc).stream().filter(b -> b.parentId() != null).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        Block block = pick(candidates);
        List<Block> parents = all(doc).stream()
                .filter(p -> !doc.isSelfOrDescendant(p.id(), block.id()))
                .toList();
        return new MoveBlock(block.id(), pick(parents).id(), randomPos());
    }

    private Op setProps(Document doc) {
        Block block = pick(all(doc));
        int kind = random.nextInt(3);
        if (kind == 0 && block.parentId() != null) {
            return new SetProps(block.id(), Op.TYPE_KEY, pickType());
        }
        if (kind == 1) {
            return new SetProps(block.id(), "checked", random.nextInt(3) == 0 ? null : random.nextBoolean());
        }
        return new SetProps(block.id(), "level", random.nextInt(3) == 0 ? null : 1 + random.nextInt(3));
    }

    private Map<String, Object> randomAttrChange() {
        return switch (random.nextInt(4)) {
            case 0 -> Attrs.of("bold", true);
            case 1 -> Attrs.of("bold", null);
            case 2 -> Attrs.of("color", random.nextBoolean() ? "red" : "blue");
            default -> Attrs.of("color", null);
        };
    }

    private String randomPos() {
        if (random.nextInt(4) == 0) {
            String low = POS_KEYS[random.nextInt(POS_KEYS.length - 1)];
            return FractionalIndex.between(low, null);
        }
        return POS_KEYS[random.nextInt(POS_KEYS.length)];
    }

    private String randomString(int maxLength) {
        int length = 1 + random.nextInt(maxLength);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String pickType() {
        return TYPES[random.nextInt(TYPES.length)];
    }

    private String newId() {
        return idPrefix + (counter++);
    }

    /** HashMap 순서에 기대지 않도록 ID 순으로 정렬해서 고른다. 시드가 같으면 결과가 같아야 한다. */
    private static List<Block> all(Document doc) {
        List<Block> blocks = new ArrayList<>();
        doc.blocks().forEach(blocks::add);
        blocks.sort(Comparator.comparing(Block::id));
        return blocks;
    }

    private <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }
}
