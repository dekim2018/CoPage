package com.copage.collab.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버의 JSON 형식이 TypeScript 구현과 같은지 적합성 벡터로 확인한다. 벡터에 나오는 모든 연산과 문서를 읽었다가 다시 쓰면
 * 원래 JSON 과 같아야 한다. 벡터는 ot-core 의 exportVectors 가 만들고, 테스트 태스크가 그 위치를 넘겨 준다.
 */
class OpJsonVectorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode cases(String name) {
        File file = new File(System.getProperty("copage.vectors.dir"), name);
        assertTrue(file.isFile(), "벡터 " + file + " 이 없다. ./gradlew :ot-core:exportVectors 를 먼저 실행한다");
        return MAPPER.readTree(file).get("cases");
    }

    private static int checkOps(JsonNode ops) {
        assertEquals(ops, OpJson.ops(OpJson.readOps(ops)));
        return ops.size();
    }

    private static void checkDocument(JsonNode document) {
        assertEquals(document, OpJson.document(OpJson.readDocument(document)));
    }

    @Test
    void 변환_벡터의_연산과_문서를_같은_모양으로_다시_쓴다() {
        int ops = 0;
        for (JsonNode c : cases("transform.json")) {
            checkDocument(c.get("start"));
            ops += checkOps(c.get("a")) + checkOps(c.get("b"));
            JsonNode expected = c.get("expected");
            if (!expected.has("error")) {
                ops += checkOps(expected.get("a")) + checkOps(expected.get("b"));
                checkDocument(expected.get("afterA"));
            }
        }
        assertTrue(ops > 0);
    }

    @Test
    void 역연산_벡터의_연산과_문서를_같은_모양으로_다시_쓴다() {
        for (JsonNode c : cases("invert.json")) {
            checkDocument(c.get("start"));
            checkOps(c.get("change"));
            checkOps(c.get("expected").get("inverse"));
            checkDocument(c.get("expected").get("restored"));
        }
    }

    @Test
    void 텍스트_편집_벡터의_정규화된_편집과_글을_같은_모양으로_다시_쓴다() {
        for (JsonNode c : cases("delta.json")) {
            JsonNode normalized = c.get("expected").get("normalized");
            assertEquals(normalized, OpJson.delta(OpJson.readDelta(normalized)));
            assertEquals(c.get("text"), OpJson.richText(OpJson.readRichText(c.get("text"))));
            // 정규화하기 전의 조각 목록을 읽으면 정규화된 편집이 된다.
            assertEquals(normalized, OpJson.delta(OpJson.readDelta(c.get("a"))));
        }
    }
}
