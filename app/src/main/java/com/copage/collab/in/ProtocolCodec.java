package com.copage.collab.in;

import com.copage.collab.domain.ClientMessage;
import com.copage.collab.domain.Cursor;
import com.copage.collab.domain.JsonFormatException;
import com.copage.collab.domain.OpJson;
import com.copage.collab.domain.Peer;
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
        try {
            return parse(text);
        } catch (JsonFormatException e) {
            throw new ProtocolException(e.getMessage(), e);
        }
    }

    private ClientMessage parse(String text) {
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
                // client_id 는 text 컬럼에 들어간다. PostgreSQL 은 text 의 U+0000 도 받지 않는다.
                if (clientId.chars().anyMatch(Character::isISOControl)) {
                    throw new ProtocolException("clientId 에 제어 문자를 쓸 수 없다");
                }
                boolean resume = node.has("resume") && OpJson.bool(node, "resume");
                yield new ClientMessage.Hello(clientId, OpJson.longValue(node, "lastRev"), resume);
            }
            case "change" -> new ClientMessage.Change(
                    OpJson.longValue(node, "clientSeq"),
                    OpJson.longValue(node, "baseRev"),
                    OpJson.readOps(node.get("ops")));
            case "presence" -> new ClientMessage.Presence(cursor(node.get("cursor")));
            case "ping" -> new ClientMessage.Ping();
            default -> throw new ProtocolException("알 수 없는 메시지: " + type);
        };
    }

    /** 커서. null 이면 편집기를 떠난 것이다. 블록 ID 는 100 글자까지, 위치는 0 이상의 정수다. */
    private static Cursor cursor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ProtocolException("cursor 는 객체여야 한다");
        }
        return new Cursor(position(node.get("anchor")), position(node.get("head")));
    }

    private static Cursor.Position position(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ProtocolException("cursor 의 anchor 와 head 는 객체여야 한다");
        }
        String blockId = OpJson.string(node, "blockId");
        int offset = OpJson.integer(node, "offset");
        if (blockId.isEmpty() || blockId.length() > 100 || offset < 0) {
            throw new ProtocolException("cursor 의 위치가 올바르지 않다");
        }
        return new Cursor.Position(blockId, offset);
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
                ArrayNode peers = node.putArray("peers");
                welcome.peers().forEach(peer -> peers.add(peer(peer)));
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
                node.put("code", nack.code());
            }
            case ServerMessage.Remote remote -> {
                node.setAll(change(remote.change()));
                node.put("t", "remote");
            }
            case ServerMessage.Role role -> {
                node.put("t", "role");
                node.put("role", role.role().name());
            }
            case ServerMessage.CommentChanged comment -> {
                node.put("t", "comment");
                node.put("threadId", comment.threadId());
            }
            case ServerMessage.Presence presence -> {
                if (presence.left()) {
                    node.put("clientId", presence.peer().clientId());
                    node.put("userId", presence.peer().userId().toString());
                    node.put("left", true);
                } else {
                    node.setAll(peer(presence.peer()));
                }
                node.put("t", "presence");
            }
        }
        return mapper.writeValueAsString(node);
    }

    private static ObjectNode peer(Peer peer) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("clientId", peer.clientId());
        node.put("userId", peer.userId().toString());
        node.put("name", peer.name());
        node.put("color", peer.color());
        if (peer.cursor() == null) {
            node.putNull("cursor");
        } else {
            ObjectNode cursor = node.putObject("cursor");
            cursor.set("anchor", position(peer.cursor().anchor()));
            cursor.set("head", position(peer.cursor().head()));
        }
        return node;
    }

    private static ObjectNode position(Cursor.Position position) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("blockId", position.blockId());
        node.put("offset", position.offset());
        return node;
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
