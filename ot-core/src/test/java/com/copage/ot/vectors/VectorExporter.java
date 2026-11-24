package com.copage.ot.vectors;

import com.copage.ot.FractionalIndex;
import com.copage.ot.Inverter;
import com.copage.ot.TransformConflictException;
import com.copage.ot.Transformer;
import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.support.RandomOps;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Component;
import com.copage.ot.text.Component.Delete;
import com.copage.ot.text.Component.Insert;
import com.copage.ot.text.Component.Retain;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java 구현으로 (입력, 기대 결과)를 계산해 JSON 파일로 내보낸다. TypeScript 구현은 Vitest 에서 같은 입력으로 계산해 비교한다.
 *
 * <p>파일 여섯 개를 만든다.
 *
 * <ul>
 *   <li>{@code delta.json}: 텍스트 편집의 정규화, 적용, 변환, 합성, 역연산, splitAt, transformPosition
 *   <li>{@code apply.json}: 문서에 연산 묶음을 적용한 결과. 적용할 수 없는 묶음이 섞여 있다
 *   <li>{@code transform.json}: 연산 묶음 두 개의 변환과, 변환 결과를 양쪽 순서로 적용한 문서
 *   <li>{@code invert.json}: 묶음의 역연산과 역연산을 적용한 문서
 *   <li>{@code fractional-index.json}: 순서 키 만들기
 *   <li>{@code client.json}: 수렴 시뮬레이션에서 클라이언트 하나가 받은 호출과 그때마다의 상태
 * </ul>
 *
 * <p>시드가 고정되어 있어 같은 코드에서는 항상 같은 파일이 나온다. 실행은 {@code ./gradlew :ot-core:exportVectors}.
 */
public final class VectorExporter {

    static final int DELTA_CASES = 3_000;
    static final int APPLY_CASES = 1_000;
    static final int TRANSFORM_CASES = 4_000;
    static final int INVERT_CASES = 2_000;
    static final int FRACTIONAL_INDEX_CASES = 500;
    static final int CLIENT_SCENARIOS = 500;

    /** 코드 단위 2개짜리 글자(이모지)를 섞어 위치가 UTF-16 코드 단위로 세어지는지 확인한다. */
    private static final String ALPHABET = "ab가나 😀";

    private VectorExporter() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("사용법: VectorExporter <출력 디렉터리>");
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("디렉터리를 만들 수 없다: " + dir);
        }
        write(dir, "delta.json", deltaCases());
        write(dir, "apply.json", applyCases());
        write(dir, "transform.json", transformCases());
        write(dir, "invert.json", invertCases());
        write(dir, "fractional-index.json", fractionalIndexCases());
        write(dir, "client.json", ClientVectors.scenarios(CLIENT_SCENARIOS, 3_000_000L));
    }

    private static void write(File dir, String name, ArrayNode cases) {
        ObjectNode root = VectorJson.object();
        root.put("version", 1);
        root.set("cases", cases);
        File file = new File(dir, name);
        VectorJson.MAPPER.writeValue(file, root);
        System.out.println(name + ": " + cases.size() + "건, " + file.length() + " 바이트");
    }

    // ----- 텍스트 편집 -----

    private static ArrayNode deltaCases() {
        ArrayNode cases = VectorJson.array();
        for (int i = 0; i < DELTA_CASES; i++) {
            long seed = 1_000_000L + i;
            Random random = new Random(seed);
            RichText text = randomText(random);
            List<Component> rawA = randomComponents(random, text.length());
            Delta a = Delta.of(rawA);
            Delta b = Delta.of(randomComponents(random, text.length()));
            int offset = random.nextInt(text.length() + 1);

            ObjectNode node = cases.addObject();
            node.put("seed", seed);
            node.set("text", VectorJson.richText(text));
            node.set("a", VectorJson.components(rawA));
            node.set("b", VectorJson.delta(b));
            node.put("offset", offset);

            ObjectNode expected = node.putObject("expected");
            expected.set("normalized", VectorJson.delta(a));
            expected.put("baseLength", a.baseLength());
            expected.put("lengthChange", a.lengthChange());
            RichText applied = null;
            try {
                applied = a.apply(text);
                expected.set("apply", VectorJson.richText(applied));
            } catch (IllegalArgumentException e) {
                expected.set("apply", VectorJson.error("argument"));
            }
            expected.set("transformLater", VectorJson.delta(a.transform(b, true)));
            expected.set("transformEarlier", VectorJson.delta(a.transform(b, false)));
            expected.set("invert", VectorJson.delta(a.invert(text)));
            Delta[] parts = a.splitAt(offset);
            ArrayNode split = expected.putArray("splitAt");
            split.add(VectorJson.delta(parts[0]));
            split.add(VectorJson.delta(parts[1]));
            expected.put("transformPosition", a.transformPosition(offset));
            if (applied != null) {
                Delta c = Delta.of(randomComponents(random, applied.length()));
                node.set("c", VectorJson.delta(c));
                expected.set("compose", VectorJson.delta(a.compose(c)));
            }
        }
        return cases;
    }

    private static RichText randomText(Random random) {
        RichText.Builder builder = new RichText.Builder();
        int segments = random.nextInt(5);
        for (int i = 0; i < segments; i++) {
            builder.append(randomString(random, 4), random.nextBoolean() ? Attrs.NONE : randomAttrs(random, false));
        }
        return builder.build();
    }

    /**
     * 정규화하기 전의 조각 목록. 같은 종류의 조각을 이어 붙이거나 삭제 뒤에 삽입을 두거나 끝에 서식 없는 retain 을 남겨서
     * 정규화 규칙까지 비교되게 한다. 이모지의 가운데를 가르는 편집도 일부러 섞는다.
     */
    private static List<Component> randomComponents(Random random, int length) {
        List<Component> out = new ArrayList<>();
        int cursor = 0;
        int pieces = 1 + random.nextInt(5);
        for (int i = 0; i < pieces; i++) {
            int remaining = length - cursor;
            int kind = random.nextInt(4);
            if (kind == 0 || remaining == 0) {
                out.add(new Insert(randomString(random, 3), random.nextInt(3) == 0 ? randomAttrs(random, false) : Attrs.NONE));
            } else {
                int count = 1 + random.nextInt(remaining);
                if (kind == 1) {
                    out.add(new Retain(count, random.nextInt(3) == 0 ? randomAttrs(random, true) : Attrs.NONE));
                } else if (kind == 2) {
                    out.add(new Delete(count));
                } else {
                    out.add(new Retain(count, Attrs.NONE));
                }
                cursor += count;
            }
        }
        return out;
    }

    private static Map<String, Object> randomAttrs(Random random, boolean allowRemove) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        int keys = 1 + random.nextInt(2);
        for (int i = 0; i < keys; i++) {
            String key = random.nextBoolean() ? "bold" : "color";
            if (allowRemove && random.nextInt(3) == 0) {
                attrs.put(key, null);
            } else {
                attrs.put(key, key.equals("bold") ? (Object) Boolean.TRUE : random.nextBoolean() ? "red" : "blue");
            }
        }
        return attrs;
    }

    private static String randomString(Random random, int maxLength) {
        int length = 1 + random.nextInt(maxLength);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int at = random.nextInt(ALPHABET.length() - 1);
            sb.append(ALPHABET.charAt(at));
            if (Character.isHighSurrogate(ALPHABET.charAt(at))) {
                sb.append(ALPHABET.charAt(at + 1));
            }
        }
        return sb.toString();
    }

    // ----- 문서에 적용 -----

    /** 문서 S 에서 만든 묶음을, S 를 다른 사람이 먼저 고친 문서에 그대로 적용한다. 길이나 블록이 어긋나 실패하는 경우가 많다. */
    private static ArrayNode applyCases() {
        ArrayNode cases = VectorJson.array();
        for (int i = 0; i < APPLY_CASES; i++) {
            long seed = 6_000_000L + i;
            Random random = new Random(seed);
            Document base = new RandomOps(random, "s").randomDocument(1 + random.nextInt(5));
            Document target = base.apply(new RandomOps(random, "x").randomChange(base, 3));
            List<Op> ops = new RandomOps(random, "a").randomChange(base, 3);

            ObjectNode node = cases.addObject();
            node.put("seed", seed);
            node.set("start", VectorJson.document(target));
            node.set("ops", VectorJson.ops(ops));
            try {
                Document applied = target.apply(ops);
                ObjectNode expected = node.putObject("expected");
                expected.set("document", VectorJson.document(applied));
                expected.set("visibleOrder", visibleOrder(applied));
            } catch (InvalidOperationException e) {
                node.set("expected", VectorJson.error("invalid"));
            }
        }
        return cases;
    }

    /** 화면에 보이는 블록 ID 를 화면 순서(형제는 순서 키, 같으면 ID 순)대로 늘어놓는다. 삭제 표시된 블록과 그 아래는 빠진다. */
    private static ArrayNode visibleOrder(Document doc) {
        ArrayNode out = VectorJson.array();
        addVisible(doc, doc.get(doc.rootId()), out);
        return out;
    }

    private static void addVisible(Document doc, Block block, ArrayNode out) {
        if (!doc.isVisible(block.id())) {
            return;
        }
        out.add(block.id());
        for (Block child : doc.children(block.id())) {
            addVisible(doc, child, out);
        }
    }

    // ----- 문서 연산의 변환 -----

    private static ArrayNode transformCases() {
        ArrayNode cases = VectorJson.array();
        for (int i = 0; i < TRANSFORM_CASES; i++) {
            long seed = 2_000_000L + i;
            Random random = new Random(seed);
            int maxOps = i % 2 == 0 ? 1 : 3;
            Document start = new RandomOps(random, "s").randomDocument(1 + random.nextInt(5));
            List<Op> a = new RandomOps(random, "a").randomChange(start, maxOps);
            List<Op> b = new RandomOps(random, "b").randomChange(start, maxOps);
            boolean aIsLater = random.nextBoolean();

            ObjectNode node = cases.addObject();
            node.put("seed", seed);
            node.set("start", VectorJson.document(start));
            node.set("a", VectorJson.ops(a));
            node.set("b", VectorJson.ops(b));
            node.put("aIsLater", aIsLater);

            ObjectNode expected = node.putObject("expected");
            Transformer.Pair pair;
            try {
                pair = Transformer.transform(a, b, aIsLater);
            } catch (TransformConflictException e) {
                node.set("expected", VectorJson.error("conflict"));
                continue;
            }
            expected.set("a", VectorJson.ops(pair.a()));
            expected.set("b", VectorJson.ops(pair.b()));
            expected.set("afterA", VectorJson.document(start.apply(a)));
            expected.set("afterB", VectorJson.document(start.apply(b)));
            expected.set("viaA", applyOrError(() -> start.apply(a).apply(pair.b())));
            expected.set("viaB", applyOrError(() -> start.apply(b).apply(pair.a())));
        }
        return cases;
    }

    private static JsonNode applyOrError(Supplier<Document> apply) {
        try {
            return VectorJson.document(apply.get());
        } catch (InvalidOperationException e) {
            return VectorJson.error("invalid");
        }
    }

    // ----- 역연산 -----

    private static ArrayNode invertCases() {
        ArrayNode cases = VectorJson.array();
        for (int i = 0; i < INVERT_CASES; i++) {
            long seed = 4_000_000L + i;
            Random random = new Random(seed);
            RandomOps generator = new RandomOps(random, "b");
            Document start = generator.randomDocument(1 + random.nextInt(6));
            // 삭제 표시된 블록이 섞인 문서에서 시작하도록 몇 번 더 편집해 둔다.
            start = start.apply(generator.randomChange(start, 4));
            List<Op> change = generator.randomChange(start, 3);
            List<Op> inverse = Inverter.invert(change, start);

            ObjectNode node = cases.addObject();
            node.put("seed", seed);
            node.set("start", VectorJson.document(start));
            node.set("change", VectorJson.ops(change));
            ObjectNode expected = node.putObject("expected");
            expected.set("inverse", VectorJson.ops(inverse));
            expected.set("restored", VectorJson.document(start.apply(change).apply(inverse)));
        }
        return cases;
    }

    // ----- 순서 키 -----

    private static ArrayNode fractionalIndexCases() {
        ArrayNode cases = VectorJson.array();
        Random random = new Random(5_000_000L);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < FRACTIONAL_INDEX_CASES; i++) {
            String before;
            String after;
            int roll = random.nextInt(10);
            if (roll == 0) {
                // 잘못된 입력: 순서가 뒤집힘, 쓸 수 없는 글자, 0 으로 끝나는 키
                String[][] invalid = {{"b", "a"}, {"a", "a"}, {"a-", null}, {null, "a0"}, {"", "!"}};
                String[] pick = invalid[random.nextInt(invalid.length)];
                before = pick[0];
                after = pick[1];
            } else {
                int at = keys.isEmpty() ? 0 : random.nextInt(keys.size() + 1);
                before = at == 0 ? null : keys.get(at - 1);
                after = at == keys.size() ? null : keys.get(at);
            }
            ObjectNode node = cases.addObject();
            node.put("before", before);
            node.put("after", after);
            try {
                String key = FractionalIndex.between(before, after);
                node.put("expected", key);
                if (roll != 0) {
                    keys.add(before == null ? 0 : keys.indexOf(before) + 1, key);
                }
            } catch (IllegalArgumentException e) {
                node.set("expected", VectorJson.error("argument"));
            }
        }
        // 맨 뒤에 붙이기와 맨 앞에 넣기를 길게 이어 간다. 세는 길이와 첫 글자 옮기기, 'z' 와 '0' 떼기를 모두 지나간다(D-24).
        // 예전 방식으로 길어진 키에서 시작하는 줄도 넣는다.
        for (String start : new String[] {null, "V", "zzzzzV", "00001", "y", "1"}) {
            String last = start;
            String first = start == null ? FractionalIndex.between(null, null) : start;
            for (int i = 0; i < 300; i++) {
                String appended = FractionalIndex.between(last, null);
                ObjectNode append = cases.addObject();
                append.put("before", last);
                append.putNull("after");
                append.put("expected", appended);
                last = appended;
                String prepended = FractionalIndex.between(null, first);
                ObjectNode prepend = cases.addObject();
                prepend.putNull("before");
                prepend.put("after", first);
                prepend.put("expected", prepended);
                first = prepended;
            }
        }
        return cases;
    }
}
