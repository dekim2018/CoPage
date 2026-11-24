package com.copage.notification.out;

import com.copage.notification.app.LiveEvents;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실시간 이벤트를 모든 노드에 전한다(`copage:live`, 5.5 의 user:{userId}:notify 를 채널 하나로). 받은 노드는 그 사용자의 열린
 * 알림 연결이 있으면 보낸다. 보낸 노드도 같은 채널로 받는다(따로 바로 보내지 않는다).
 */
public final class RedisLiveEvents implements LiveEvents, MessageListener {

    public static final String CHANNEL = "copage:live";

    private static final System.Logger LOG = System.getLogger(RedisLiveEvents.class.getName());

    /** 받은 이벤트를 이 노드의 연결로 넘기는 곳. */
    public interface LocalDelivery {
        void deliver(UUID userId, String type, JsonNode data);
    }

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final LocalDelivery local;

    public RedisLiveEvents(StringRedisTemplate redis, JsonMapper mapper, LocalDelivery local) {
        this.redis = redis;
        this.mapper = mapper;
        this.local = local;
    }

    @Override
    public void send(UUID userId, String type, Map<String, Object> data) {
        ObjectNode message = mapper.createObjectNode();
        message.put("userId", userId.toString());
        message.put("type", type);
        message.set("data", mapper.valueToTree(data));
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(message));
        } catch (DataAccessException e) {
            LOG.log(System.Logger.Level.WARNING, "실시간 이벤트를 보내지 못했다", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode json = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            local.deliver(UUID.fromString(json.get("userId").asString()), json.get("type").asString(), json.get("data"));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "실시간 이벤트를 읽지 못했다", e);
        }
    }
}
