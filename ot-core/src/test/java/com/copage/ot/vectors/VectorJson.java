package com.copage.ot.vectors;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 적합성 벡터에 쓰는 JSON 형식. TypeScript 쪽 {@code frontend/src/collab/ot/json.ts} 와 같은 모양이어야 한다.
 *
 * <p>연산은 docs/05-PROTOCOL.md 7.2 의 형식({@code {"op": "text", "blockId": ..., "delta": [...]}})을 따른다. 문서는 블록을 ID
 * 순으로 늘어놓은 배열로 적는다. 서식과 속성의 키는 이름 순이다.
 */
final class VectorJson {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private VectorJson() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** 서식이나 속성 값. 지금 쓰는 값은 문자열, 참거짓, 정수, null 이다. */
    static JsonNode value(Object value) {
        return switch (value) {
            case null -> MAPPER.nullNode();
            case Boolean b -> MAPPER.getNodeFactory().booleanNode(b);
            case Integer i -> MAPPER.getNodeFactory().numberNode(i);
            case Long l -> MAPPER.getNodeFactory().numberNode(l);
            case String s -> MAPPER.getNodeFactory().stringNode(s);
            case List<?> list -> {
                ArrayNode out = array();
                list.forEach(item -> out.add(value(item)));
                yield out;
            }
            case Map<?, ?> map -> {
                ObjectNode out = object();
                new TreeMap<>(map).forEach((k, v) -> out.set((String) k, value(v)));
                yield out;
            }
            default -> throw new IllegalArgumentException("JSON 으로 적을 수 없는 값: " + value.getClass());
        };
    }

    static ObjectNode attrs(Map<String, Object> attrs) {
        ObjectNode out = object();
        new TreeMap<>(attrs).forEach((k, v) -> out.set(k, value(v)));
        return out;
    }

    static ArrayNode richText(RichText text) {
        ArrayNode out = array();
        for (Segment segment : text.segments()) {
            ObjectNode node = out.addObject();
            node.put("insert", segment.text());
            if (!segment.attrs().isEmpty()) {
                node.set("attrs", attrs(segment.attrs()));
            }
        }
        return out;
    }

    static ArrayNode components(List<Component> components) {
        ArrayNode out = array();
        for (Component component : components) {
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

    static ArrayNode delta(Delta delta) {
        return components(delta.components());
    }

    static ObjectNode op(Op op) {
        ObjectNode node = object();
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

    static ArrayNode ops(List<Op> ops) {
        ArrayNode out = array();
        ops.forEach(op -> out.add(op(op)));
        return out;
    }

    static ObjectNode document(Document doc) {
        List<Block> blocks = new ArrayList<>();
        doc.blocks().forEach(blocks::add);
        blocks.sort(Comparator.comparing(Block::id));
        ObjectNode node = object();
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

    /** 계산이 예외로 끝났다는 표시. kind 는 invalid(적용 불가), conflict(변환 충돌), argument(잘못된 인자) 중 하나다. */
    static ObjectNode error(String kind) {
        ObjectNode node = object();
        node.put("error", kind);
        return node;
    }
}
