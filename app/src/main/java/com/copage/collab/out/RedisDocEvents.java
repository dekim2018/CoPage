package com.copage.collab.out;

import com.copage.collab.app.DocEvents;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.domain.ServerMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 문서 알림을 그 문서를 맡은 노드에 전한다(`copage:doc-event`, D-46). 모든 노드가 받고, 세션을 가진 노드만 연결들에 보낸다.
 * 값은 "문서ID:스레드ID" 다.
 */
public final class RedisDocEvents implements DocEvents, MessageListener {

    public static final String CHANNEL = "copage:doc-event";

    private static final System.Logger LOG = System.getLogger(RedisDocEvents.class.getName());

    private final StringRedisTemplate redis;
    private final DocumentSessions sessions;

    public RedisDocEvents(StringRedisTemplate redis, DocumentSessions sessions) {
        this.redis = redis;
        this.sessions = sessions;
    }

    @Override
    public void commentChanged(UUID docId, UUID threadId) {
        try {
            redis.convertAndSend(CHANNEL, docId + ":" + threadId);
        } catch (DataAccessException e) {
            // 받지 못한 화면은 다음에 댓글 창을 열 때 다시 읽는다.
            LOG.log(System.Logger.Level.WARNING, "댓글 변경을 알리지 못했다", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String value = new String(message.getBody(), StandardCharsets.UTF_8);
        int colon = value.indexOf(':');
        try {
            UUID docId = UUID.fromString(value.substring(0, colon));
            sessions.announce(docId, new ServerMessage.CommentChanged(value.substring(colon + 1)));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "문서 알림을 읽지 못했다: " + value, e);
        }
    }
}
