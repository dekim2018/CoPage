package com.copage.permission.out;

import com.copage.permission.app.PermissionChange;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 권한 변경을 다른 노드에 전한다 (docs/03-ARCHITECTURE.md 5.5 의 perm:changed, D-43). 변경한 노드는 자기 세션에 바로 알리고
 * 이 채널에도 싣는다. 받은 노드는 보낸 노드가 자기가 아니면 자기 세션에 알린다. 그 문서를 맡은 노드는 하나라 받는 쪽 중 하나만
 * 실제로 연결을 다시 계산한다.
 *
 * <p>Redis pub/sub 은 받는 쪽이 없거나 끊겨 있으면 버린다. 놓친 노드의 연결은 다음 재접속 때 새 티켓으로 바른 등급을 받는다.
 */
public final class RedisPermissionRelay implements MessageListener {

    public static final String CHANNEL = "copage:perm";

    private static final System.Logger LOG = System.getLogger(RedisPermissionRelay.class.getName());

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final String nodeId;
    private final Consumer<PermissionChange> local;

    public RedisPermissionRelay(
            StringRedisTemplate redis, JsonMapper mapper, String nodeId, Consumer<PermissionChange> local) {
        this.redis = redis;
        this.mapper = mapper;
        this.nodeId = nodeId;
        this.local = local;
    }

    public void publish(PermissionChange change) {
        ObjectNode json = mapper.createObjectNode();
        json.put("origin", nodeId);
        if (change.pageIds() != null) {
            ArrayNode pages = json.putArray("pageIds");
            change.pageIds().forEach(id -> pages.add(id.toString()));
        }
        if (change.userId() != null) {
            json.put("userId", change.userId().toString());
        }
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(json));
        } catch (DataAccessException e) {
            LOG.log(System.Logger.Level.WARNING, "권한 변경을 다른 노드에 전하지 못했다", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode json = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            if (nodeId.equals(json.path("origin").asString(""))) {
                return;
            }
            Set<UUID> pages = null;
            if (json.has("pageIds")) {
                pages = new HashSet<>();
                for (JsonNode id : json.get("pageIds")) {
                    pages.add(UUID.fromString(id.asString()));
                }
            }
            UUID user = json.has("userId") ? UUID.fromString(json.get("userId").asString()) : null;
            local.accept(new PermissionChange(pages, user));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "다른 노드의 권한 변경을 읽지 못했다", e);
        }
    }
}
