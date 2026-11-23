package com.copage.ot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.support.Iterations;
import com.copage.ot.support.RandomOps;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 변환 함수의 핵심 성질(TP1)을 무작위 사례로 검사한다.
 *
 * <p>문서 S 에서 출발한 묶음 a 와 b 에 대해, "a 다음에 변환된 b"와 "b 다음에 변환된 a"가 같은 문서를 만들어야 한다.
 * 변환이 충돌로 끝나거나 변환된 연산을 적용할 수 없는 쌍은 서버가 거부하는 경우라서 비교 대상에서 뺀다. 다만 그런 쌍이
 * 너무 많으면 검사가 의미를 잃으므로 비율도 함께 확인한다.
 */
class TransformerPropertyTest {

    @Test
    void 연산_하나씩인_두_묶음은_어느_순서로_적용해도_같은_문서가_된다() {
        Stats stats = run(Iterations.of(60_000), 10_000L, 1);
        assertTrue(stats.compared > stats.total / 2, "비교한 쌍이 절반도 안 된다: " + stats);
    }

    @Test
    void 연산_여러_개인_두_묶음은_어느_순서로_적용해도_같은_문서가_된다() {
        Stats stats = run(Iterations.of(40_000), 500_000L, 3);
        assertTrue(stats.compared > stats.total / 3, "비교한 쌍이 3분의 1도 안 된다: " + stats);
    }

    private static Stats run(int runs, long seedBase, int maxOps) {
        Stats stats = new Stats();
        for (int i = 0; i < runs; i++) {
            long seed = seedBase + i;
            Random random = new Random(seed);
            Document start = new RandomOps(random, "s").randomDocument(1 + random.nextInt(5));
            List<Op> a = new RandomOps(random, "a").randomChange(start, maxOps);
            List<Op> b = new RandomOps(random, "b").randomChange(start, maxOps);
            stats.total++;

            Transformer.Pair pair;
            try {
                pair = Transformer.transform(a, b, true);
            } catch (TransformConflictException e) {
                stats.conflicts++;
                continue;
            }
            Document viaA = tryApply(start.apply(a), pair.b());
            Document viaB = tryApply(start.apply(b), pair.a());
            if (viaA == null || viaB == null) {
                stats.invalid++;
                continue;
            }
            stats.compared++;
            assertEquals(viaA, viaB, "시드 " + seed + "\n시작 문서\n" + start + "a " + a + "\nb " + b + "\na' " + pair.a()
                    + "\nb' " + pair.b());
        }
        System.out.println("TP1 (묶음당 최대 " + maxOps + "개): " + stats);
        return stats;
    }

    private static Document tryApply(Document doc, List<Op> ops) {
        try {
            return doc.apply(ops);
        } catch (InvalidOperationException e) {
            return null;
        }
    }

    private static final class Stats {
        int total;
        int compared;
        int conflicts;
        int invalid;

        @Override
        public String toString() {
            return "전체 " + total + ", 비교 " + compared + ", 변환 충돌 " + conflicts + ", 적용 불가 " + invalid;
        }
    }
}
