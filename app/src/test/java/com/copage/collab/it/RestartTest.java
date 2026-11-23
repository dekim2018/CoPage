package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.CoPageApplication;
import com.copage.collab.app.TicketService;
import com.copage.collab.domain.OpJson;
import com.copage.collab.domain.PageRole;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import com.copage.support.TestInfra;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * 2단계 완료 기준의 "서버를 재시작해도 내용이 남는다" (docs/12-ROADMAP.md). 같은 DB 를 바라보는 서버를 띄워 편집하고
 * 완전히 내린 뒤, 새로 띄운 서버에 접속하면 같은 문서를 받는다.
 */
class RestartTest {

    private static ConfigurableApplicationContext start(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(TestInfra.properties());
        properties.put("server.port", 0);
        properties.putAll(extra);
        // properties() 로 넘기면 application.yml 보다 우선순위가 낮다. 명령줄 인자로 넘겨 테스트 DB 를 가리키게 한다.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(CoPageApplication.class).run(args);
    }

    private static URI uri(ConfigurableApplicationContext context, UUID docId) {
        String port = context.getEnvironment().getProperty("local.server.port");
        String ticket = context.getBean(TicketService.class).issue(UUID.randomUUID(), docId, PageRole.EDIT);
        return URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=1");
    }

    @Test
    void 서버를_내렸다_다시_띄워도_같은_문서를_받는다() throws Exception {
        UUID docId = UUID.randomUUID();
        String root = docId.toString();
        Document before;

        // 스냅샷 간격을 줄여 스냅샷과 그 뒤 연산을 함께 읽는 경로를 지나게 한다.
        try (ConfigurableApplicationContext first = start(Map.of("copage.collab.snapshot-interval", 3))) {
            TestClient client = new TestClient("writer");
            client.connect(uri(first, docId));
            while (client.session == null) {
                client.process(5_000);
            }
            client.edit(List.of(new InsertBlock("X", root, "a", "paragraph", Attrs.NONE, RichText.of("회의록"))));
            for (int i = 0; i < 7; i++) {
                client.edit(List.of(new TextOp("X", Delta.builder().insert(String.valueOf(i)).build())));
                while (client.session.hasPending()) {
                    client.process(5_000);
                }
            }
            before = client.session.confirmed();
            assertEquals(8, client.session.confirmedRev());
            client.disconnect();
        }

        try (ConfigurableApplicationContext second = start(Map.of())) {
            TestClient reader = new TestClient("reader");
            reader.connect(uri(second, docId));
            JsonNode welcome = reader.take();

            assertEquals(8, welcome.get("rev").longValue());
            assertEquals(before, OpJson.readDocument(welcome.get("snapshot")));
            assertTrue(OpJson.readDocument(welcome.get("snapshot")).get("X").text().plainText().endsWith("회의록"));
        }
    }
}
