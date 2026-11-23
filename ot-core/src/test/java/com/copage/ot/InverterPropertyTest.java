package com.copage.ot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.support.Iterations;
import com.copage.ot.support.RandomOps;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class InverterPropertyTest {

    @Test
    void 묶음을_적용하고_역연산을_적용하면_보이는_내용이_원래대로_돌아온다() {
        int runs = Iterations.of(30_000);
        for (int i = 0; i < runs; i++) {
            long seed = 900_000L + i;
            Random random = new Random(seed);
            RandomOps generator = new RandomOps(random, "b");
            Document start = generator.randomDocument(1 + random.nextInt(6));
            // 삭제 표시된 블록이 섞인 문서에서 시작하도록 몇 번 더 편집해 둔다.
            start = start.apply(generator.randomChange(start, 4));
            List<Op> change = generator.randomChange(start, 3);

            Document restored = start.apply(change).apply(Inverter.invert(change, start));

            assertEquals(
                    start.renderVisible(),
                    restored.renderVisible(),
                    "시드 " + seed + " 묶음 " + change + " 역연산 " + Inverter.invert(change, start));
        }
    }
}
