package com.copage.collab.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copage.collab.domain.ClientMessage;
import com.copage.collab.domain.PageRole;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.StoredChange;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Delta;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProtocolCodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ProtocolCodec codec = new ProtocolCodec(MAPPER);

    @Test
    void 규약_문서의_change_예시를_읽는다() {
        String text = """
                {
                  "t": "change",
                  "clientSeq": 42,
                  "baseRev": 120,
                  "ops": [
                    { "op": "text", "blockId": "b_02", "delta": [{ "retain": 5 }, { "insert": " 초안" }] },
                    { "op": "setProps", "blockId": "b_02", "key": "checked", "value": true }
                  ]
                }
                """;

        ClientMessage message = codec.read(text);

        assertEquals(
                new ClientMessage.Change(42, 120, List.of(
                        new TextOp("b_02", Delta.builder().retain(5).insert(" 초안").build()),
                        new SetProps("b_02", "checked", true))),
                message);
    }

    @Test
    void hello_와_ping_을_읽는다() {
        assertEquals(new ClientMessage.Hello("tab-1", 0, false), codec.read(json("{'t':'hello','clientId':'tab-1','lastRev':0}")));
        assertEquals(
                new ClientMessage.Hello("tab-1", 7, true),
                codec.read(json("{'t':'hello','clientId':'tab-1','lastRev':7,'resume':true}")));
        assertEquals(new ClientMessage.Ping(), codec.read(json("{'t':'ping'}")));
    }

    @Test
    void 규약에_맞지_않는_메시지는_ProtocolException_을_던진다() {
        List<String> invalid = List.of(
                "not json",
                "[]",
                json("{'t':'unknown'}"),
                json("{'t':'hello','lastRev':0}"),
                json("{'t':'hello','clientId':'','lastRev':0}"),
                json("{'t':'hello','clientId':'a\\u0000b','lastRev':0}"),
                json("{'t':'change','clientSeq':1,'baseRev':0,'ops':[{'op':'text','blockId':'x','delta':[{'retain':0}]}]}"),
                json("{'t':'change','clientSeq':1,'baseRev':0,'ops':[{'op':'explode','blockId':'x'}]}"),
                json("{'t':'change','clientSeq':'1','baseRev':0,'ops':[]}"));
        for (String text : invalid) {
            assertThrows(ProtocolException.class, () -> codec.read(text), text);
        }
    }

    @Test
    void remote_에는_재접속한_클라이언트가_자기_묶음을_알아볼_수_있게_clientSeq_를_싣는다() {
        UUID user = UUID.fromString("00000000-0000-0000-0000-000000000001");
        StoredChange change = new StoredChange(
                3, user, "tab-1", 9, List.of(new SetProps("b", "checked", false)), Instant.EPOCH);

        JsonNode node = MAPPER.readTree(codec.write(new ServerMessage.Remote(change)));

        assertEquals(MAPPER.readTree("""
                {"t":"remote","rev":3,"userId":"00000000-0000-0000-0000-000000000001","clientId":"tab-1","clientSeq":9,
                 "ops":[{"op":"setProps","blockId":"b","key":"checked","value":false}]}
                """), node);
    }

    @Test
    void welcome_은_스냅샷이나_놓친_묶음_중_하나를_싣는다() {
        JsonNode withOps = MAPPER.readTree(codec.write(new ServerMessage.Welcome(5, PageRole.EDIT, true, null, List.of())));
        assertEquals(MAPPER.readTree("""
                {"t":"welcome","rev":5,"role":"EDIT","rebase":true,"ops":[],"peers":[]}
                """), withOps);
    }

    /** 테스트를 읽기 쉽게 작은따옴표로 쓴 JSON 을 큰따옴표로 바꾼다. */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }
}
