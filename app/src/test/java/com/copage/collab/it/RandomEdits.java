package com.copage.collab.it;

import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.DeleteBlock;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.MergeBlock;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 통합 테스트용 무작위 편집. 서버의 입력 검사를 통과하는 블록 종류와 서식만 쓴다. 지금 화면 문서에 적용할 수 있는 연산만
 * 만들지만, 서버에 도착할 때는 다른 사람의 편집과 겹쳐 거부될 수 있다.
 */
final class RandomEdits {

    private static final String[] POS = {"a", "m", "t", "z"};
    private static final String ALPHABET = "abc가나 ";

    private final Random random;
    private final String idPrefix;
    private int counter;

    RandomEdits(Random random, String idPrefix) {
        this.random = random;
        this.idPrefix = idPrefix;
    }

    Op next(Document doc) {
        List<Block> live = live(doc);
        int roll = random.nextInt(100);
        if (live.isEmpty() || roll < 15) {
            return new InsertBlock(newId(), doc.rootId(), pick(POS), "paragraph", Attrs.NONE, RichText.of(word()));
        }
        Block block = pick(live);
        int length = block.text().length();
        if (roll < 60) {
            int at = random.nextInt(length + 1);
            return new TextOp(block.id(), Delta.builder().retain(at).insert(word(), random.nextInt(4) == 0
                    ? Attrs.of("bold", true) : Attrs.NONE).build());
        }
        if (roll < 72 && length > 0) {
            int at = random.nextInt(length);
            return new TextOp(block.id(), Delta.builder().retain(at).delete(1 + random.nextInt(length - at)).build());
        }
        if (roll < 84) {
            return new SplitBlock(block.id(), random.nextInt(length + 1), newId(), block.parentId(), pick(POS), "paragraph",
                    Attrs.NONE);
        }
        if (roll < 94 && live.size() > 1) {
            Block target = pick(live.stream().filter(b -> !b.id().equals(block.id())).toList());
            return new MergeBlock(block.id(), target.id(), target.text().length());
        }
        return new DeleteBlock(block.id(), true);
    }

    /** 루트 바로 아래의 보이는 블록. 자식이 없는 블록만 써서 합치기가 자식 때문에 거부되지 않게 한다. */
    private static List<Block> live(Document doc) {
        List<Block> out = new ArrayList<>();
        for (Block block : doc.blocks()) {
            if (doc.rootId().equals(block.parentId()) && !block.deleted()) {
                out.add(block);
            }
        }
        out.sort(Comparator.comparing(Block::id));
        return out;
    }

    private String word() {
        StringBuilder out = new StringBuilder();
        int length = 1 + random.nextInt(3);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private String newId() {
        return idPrefix + "-" + counter++;
    }

    private <T> T pick(T[] items) {
        return items[random.nextInt(items.length)];
    }

    private <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }
}
