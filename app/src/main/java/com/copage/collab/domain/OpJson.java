package com.copage.collab.domain;

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
import com.copage.ot.text.Component;
import com.copage.ot.text.Component.Delete;
import com.copage.ot.text.Component.Insert;
import com.copage.ot.text.Component.Retain;
import com.copage.ot.text.Delta;
import com.copage.ot.text.RichText;
import com.copage.ot.text.Segment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 연산과 문서의 JSON 형식. TypeScript 의 {@code frontend/src/collab/ot/json.ts} 와 적합성 벡터 생성기(ot-core 테스트의
 * VectorJson)가 쓰는 모양과 같다.
 *
 * <p>연산은 docs/05-PROTOCOL.md 7.2 의 형식이다. 문서는 블록을 ID 순으로 늘어놓은 배열로 적는다. 서식과 속성의 키는 이름
 * 순이고, 조각과 글의 서식은 비어 있으면 적지 않는다. 읽다가 형식이 맞지 않으면 {@link JsonFormatException} 을 던진다.
 *
 * <p>WebSocket 메시지(in)와 연산 로그, 스냅샷(out)이 같은 형식을 쓴다.
 */
public final class OpJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private OpJson() {
    }

    // ----- 내보내기 -----

    public static JsonNode value(Object value) {
        return switch (value) {
            case null -> NODES.nullNode();
            case Boolean b -> NODES.booleanNode(b);
            case Integer i -> NODES.numberNode(i);
            case Long l -> NODES.numberNode(l);
            case Double d -> NODES.numberNode(d);
            case String s -> NODES.stringNode(s);
            case List<?> list -> {
                ArrayNode out = NODES.arrayNode();
                list.forEach(item -> out.add(value(item)));
                yield out;
            }
            case Map<?, ?> map -> {
                ObjectNode out = NODES.objectNode();
                new TreeMap<>(map).forEach((k, v) -> out.set((String) k, value(v)));
                yield out;
            }
            default -> throw new IllegalArgumentException("JSON 으로 적을 수 없는 값: " + value.getClass());
        };
    }

    public static ObjectNode attrs(Map<String, Object> attrs) {
        ObjectNode out = NODES.objectNode();
        new TreeMap<>(attrs).forEach((k, v) -> out.set(k, value(v)));
        return out;
    }

    public static ArrayNode richText(RichText text) {
        ArrayNode out = NODES.arrayNode();
        for (Segment segment : text.segments()) {
            ObjectNode node = out.addObject();
            node.put("insert", segment.text());
            if (!segment.attrs().isEmpty()) {
                node.set("attrs", attrs(segment.attrs()));
            }
        }
        return out;
    }

    public static ArrayNode delta(Delta delta) {
        ArrayNode out = NODES.arrayNode();
        for (Component component : delta.components()) {
            ObjectNode node = out.addObject();
            switch (component) {
                case Retain retain -> {
                    node.put("retain", retain.count());
                    if (!retain.attrs().isEmpty()) {
                        node.set("attrs", attrs(retain.attrs()));
                    }
                }
                case Insert insert -> {
                    node.put("insert", insert.text());
                    if (!insert.attrs().isEmpty()) {
                        node.set("attrs", attrs(insert.attrs()));
                    }
                }
                case Delete delete -> node.put("delete", delete.count());
            }
        }
        return out;
    }

    public static ObjectNode op(Op op) {
        ObjectNode node = NODES.objectNode();
        switch (op) {
            case TextOp text -> {
                node.put("op", "text");
                node.put("blockId", text.blockId());
                node.set("delta", delta(text.delta()));
            }
            case SplitBlock split -> {
                node.put("op", "split");
                node.put("blockId", split.blockId());
                node.put("offset", split.offset());
                node.put("newBlockId", split.newBlockId());
                node.put("parentId", split.parentId());
                node.put("pos", split.pos());
                node.put("type", split.type());
                node.set("props", attrs(split.props()));
            }
            case MergeBlock merge -> {
                node.put("op", "merge");
                node.put("blockId", merge.blockId());
                node.put("intoId", merge.intoId());
                node.put("intoLength", merge.intoLength());
            }
            case InsertBlock insert -> {
                node.put("op", "insertBlock");
                node.put("blockId", insert.blockId());
                node.put("parentId", insert.parentId());
                node.put("pos", insert.pos());
                node.put("type", insert.type());
                node.set("props", attrs(insert.props()));
                node.set("text", richText(insert.text()));
            }
            case DeleteBlock delete -> {
                node.put("op", "deleteBlock");
                node.put("blockId", delete.blockId());
                node.put("deleted", delete.deleted());
            }
            case MoveBlock move -> {
                node.put("op", "moveBlock");
                node.put("blockId", move.blockId());
                node.put("parentId", move.parentId());
                node.put("pos", move.pos());
            }
            case SetProps setProps -> {
                node.put("op", "setProps");
                node.put("blockId", setProps.blockId());
                node.put("key", setProps.key());
                node.set("value", value(setProps.value()));
            }
        }
        return node;
    }

    public static ArrayNode ops(List<Op> ops) {
        ArrayNode out = NODES.arrayNode();
        ops.forEach(op -> out.add(op(op)));
        return out;
    }

    public static ObjectNode document(Document doc) {
        List<Block> blocks = new ArrayList<>();
        doc.blocks().forEach(blocks::add);
        blocks.sort(Comparator.comparing(Block::id));
        ObjectNode node = NODES.objectNode();
        node.put("root", doc.rootId());
        ArrayNode out = node.putArray("blocks");
        for (Block block : blocks) {
            ObjectNode b = out.addObject();
            b.put("id", block.id());
            b.put("type", block.type());
            b.set("props", attrs(block.props()));
            b.set("text", richText(block.text()));
            b.put("parentId", block.parentId());
            b.put("pos", block.pos());
            b.put("deleted", block.deleted());
        }
        return node;
    }

    // ----- 읽기 -----

    public static Object readValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(item -> out.add(readValue(item)));
            return Collections.unmodifiableList(out);
        }
        if (node.isObject()) {
            return readAttrs(node);
        }
        throw new JsonFormatException("읽을 수 없는 값: " + node);
    }

    public static Map<String, Object> readAttrs(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new JsonFormatException("attrs 는 객체여야 한다");
        }
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            out.put(entry.getKey(), readValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    public static RichText readRichText(JsonNode node) {
        RichText.Builder builder = new RichText.Builder();
        for (JsonNode item : array(node, "text")) {
            builder.append(string(item, "insert"), readAttrs(item.get("attrs")));
        }
        return builder.build();
    }

    public static Delta readDelta(JsonNode node) {
        Delta.Builder builder = Delta.builder();
        try {
            for (JsonNode item : array(node, "delta")) {
                if (!item.isObject()) {
                    throw new JsonFormatException("조각은 객체여야 한다");
                }
                if (item.has("retain")) {
                    builder.push(new Retain(integer(item, "retain"), readAttrs(item.get("attrs"))));
                } else if (item.has("insert")) {
                    builder.push(new Insert(string(item, "insert"), readAttrs(item.get("attrs"))));
                } else if (item.has("delete")) {
                    builder.push(new Delete(integer(item, "delete")));
                } else {
                    throw new JsonFormatException("조각은 retain, insert, delete 중 하나여야 한다");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new JsonFormatException("조각이 올바르지 않다: " + e.getMessage(), e);
        }
        return builder.build();
    }

    public static Op readOp(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new JsonFormatException("연산은 객체여야 한다");
        }
        String kind = string(node, "op");
        String blockId = string(node, "blockId");
        return switch (kind) {
            case "text" -> new TextOp(blockId, readDelta(node.get("delta")));
            case "split" -> new SplitBlock(
                    blockId,
                    integer(node, "offset"),
                    string(node, "newBlockId"),
                    string(node, "parentId"),
                    string(node, "pos"),
                    string(node, "type"),
                    readAttrs(node.get("props")));
            case "merge" -> new MergeBlock(blockId, string(node, "intoId"), integer(node, "intoLength"));
            case "insertBlock" -> new InsertBlock(
                    blockId,
                    string(node, "parentId"),
                    string(node, "pos"),
                    string(node, "type"),
                    readAttrs(node.get("props")),
                    node.has("text") ? readRichText(node.get("text")) : RichText.EMPTY);
            case "deleteBlock" -> new DeleteBlock(blockId, bool(node, "deleted"));
            case "moveBlock" -> new MoveBlock(blockId, string(node, "parentId"), string(node, "pos"));
            case "setProps" -> {
                if (!node.has("value")) {
                    throw new JsonFormatException("setProps 에 value 가 없다");
                }
                yield new SetProps(blockId, string(node, "key"), readValue(node.get("value")));
            }
            default -> throw new JsonFormatException("알 수 없는 연산: " + kind);
        };
    }

    public static List<Op> readOps(JsonNode node) {
        List<Op> out = new ArrayList<>();
        for (JsonNode item : array(node, "ops")) {
            out.add(readOp(item));
        }
        return out;
    }

    /** 문서를 읽는다. 블록의 부모가 없거나 루트가 맞지 않으면 {@link JsonFormatException} 을 던진다. */
    public static Document readDocument(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new JsonFormatException("문서는 객체여야 한다");
        }
        List<Block> blocks = new ArrayList<>();
        for (JsonNode item : array(node.get("blocks"), "blocks")) {
            JsonNode parent = item.get("parentId");
            blocks.add(new Block(
                    string(item, "id"),
                    string(item, "type"),
                    readAttrs(item.get("props")),
                    readRichText(item.get("text")),
                    parent == null || parent.isNull() ? null : string(item, "parentId"),
                    string(item, "pos"),
                    bool(item, "deleted")));
        }
        try {
            return Document.of(string(node, "root"), blocks);
        } catch (IllegalArgumentException e) {
            throw new JsonFormatException("문서가 올바르지 않다: " + e.getMessage(), e);
        }
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new JsonFormatException(field + " 는 배열이어야 한다");
        }
        return node;
    }

    public static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new JsonFormatException(field + " 는 문자열이어야 한다");
        }
        return value.stringValue();
    }

    public static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new JsonFormatException(field + " 는 정수여야 한다");
        }
        return value.intValue();
    }

    public static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new JsonFormatException(field + " 는 정수여야 한다");
        }
        return value.longValue();
    }

    public static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new JsonFormatException(field + " 는 참거짓이어야 한다");
        }
        return value.booleanValue();
    }
}
