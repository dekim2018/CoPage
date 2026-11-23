package com.copage.collab.in;

import com.copage.collab.domain.ClientMessage;
import com.copage.collab.domain.ServerMessage;
import com.copage.collab.domain.StoredChange;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * WebSocket 메시지와 JSON 텍스트 사이의 변환 (docs/05-PROTOCOL.md). 메시지 종류는 {@code t} 필드로 가른다.
 *
 * <p>remote 와 welcome 의 묶음에는 7.3 표의 필드에 더해 clientSeq 를 싣는다. 재접속한 클라이언트가 자기 묶음을 알아보려면
 * (clientId, clientSeq) 가 둘 다 필요하기 때문이다 (7.5).
 */
public final class ProtocolCodec {

    private final JsonMapper mapper;

    public ProtocolCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public ClientMessage read(String text) {
        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (JacksonException e) {
            throw new ProtocolException("JSON 이 아니다", e);
        }
        if (node == null || !node.isObject()) {
            throw new ProtocolException("메시지는 객체여야 한다");
        }
        String type = OpJson.string(node, "t");
        return switch (type) {
            case "hello" -> {
                String clientId = OpJson.string(node, "clientId");
                if (clientId.isEmpty() || clientId.length() > 64) {
                    throw new ProtocolException("clientId 는 1~64 글자여야 한다");
                }
                boolean resume = node.has("resume") && OpJson.bool(node, "resume");
                yield new ClientMessage.Hello(clientId, OpJson.longValue(node, "lastRev"), resume);
            }
            case "change" -> new ClientMessage.Change(
                    OpJson.longValue(node, "clientSeq"),
                    OpJson.longValue(node, "baseRev"),
                    OpJson.readOps(node.get("ops")));
            case "presence" -> new ClientMessage.Presence();
            case "ping" -> new ClientMessage.Ping();
            default -> throw new ProtocolException("알 수 없는 메시지: " + type);
        };
    }

    public String write(ServerMessage message) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        switch (message) {
            case ServerMessage.Welcome welcome -> {
                node.put("t", "welcome");
                node.put("rev", welcome.rev());
                node.put("role", welcome.role().name());
                node.put("rebase", welcome.rebase());
                if (welcome.snapshot() != null) {
                    node.set("snapshot", OpJson.document(welcome.snapshot()));
                } else {
                    ArrayNode ops = node.putArray("ops");
                    welcome.ops().forEach(change -> ops.add(change(change)));
                }
                // 프레즌스는 아직 없어서 항상 비어 있다.
                node.putArray("peers");
            }
            case ServerMessage.Ack ack -> {
                node.put("t", "ack");
                node.put("clientSeq", ack.clientSeq());
                node.put("rev", ack.rev());
            }
            case ServerMessage.Nack nack -> {
                node.put("t", "nack");
                node.put("clientSeq", nack.clientSeq());
                node.put("reason", nack.reason());
                node.put("rev", nack.rev());
            }
            case ServerMessage.Remote remote -> {
                node.setAll(change(remote.change()));
                node.put("t", "remote");
            }
        }
        return mapper.writeValueAsString(node);
    }

    private static ObjectNode change(StoredChange change) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("rev", change.rev());
        node.put("userId", change.userId().toString());
        node.put("clientId", change.clientId());
        node.put("clientSeq", change.clientSeq());
        node.set("ops", OpJson.ops(change.ops()));
        return node;
    }
}
