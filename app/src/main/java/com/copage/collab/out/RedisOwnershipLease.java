package com.copage.collab.out;

import com.copage.collab.app.OwnershipLease;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 의 문서 소유 리스 (docs/03-ARCHITECTURE.md 5.3 의 2번, D-43). 키 `copage:doc:{docId}:owner`, 값은 노드 이름, 만료는
 * ttl. 잡기, 이어 쥐기, 풀기는 값이 이 노드인지 보고 바꾸는 Lua 한 번이라 다른 노드의 리스를 건드리지 않는다.
 */
public final class RedisOwnershipLease implements OwnershipLease {

    /** 넘겨 달라는 요청을 싣는 채널. 값은 문서 ID 다. */
    public static final String HANDOFF_CHANNEL = "copage:handoff";

    private static final System.Logger LOG = System.getLogger(RedisOwnershipLease.class.getName());

    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local owner = redis.call('GET', KEYS[1])
            if owner == false then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if owner == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RENEW = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String nodeId;
    private final String ttlMillis;

    public RedisOwnershipLease(StringRedisTemplate redis, String nodeId, Duration ttl) {
        this.redis = redis;
        this.nodeId = nodeId;
        this.ttlMillis = Long.toString(ttl.toMillis());
    }

    /** 저장소에 닿지 못하면 잡지 못한 것으로 본다. 클라이언트는 4009 를 받고 잠시 뒤 다시 붙는다. */
    @Override
    public boolean tryAcquire(UUID docId) {
        try {
            return Long.valueOf(1).equals(redis.execute(ACQUIRE, List.of(key(docId)), nodeId, ttlMillis));
        } catch (DataAccessException e) {
            LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 소유 리스를 잡지 못했다(저장소)", e);
            return false;
        }
    }

    @Override
    public Renewal renew(UUID docId) {
        try {
            return Long.valueOf(1).equals(redis.execute(RENEW, List.of(key(docId)), nodeId, ttlMillis))
                    ? Renewal.RENEWED
                    : Renewal.LOST;
        } catch (DataAccessException e) {
            return Renewal.UNKNOWN;
        }
    }

    @Override
    public void release(UUID docId) {
        try {
            redis.execute(RELEASE, List.of(key(docId)), nodeId);
        } catch (DataAccessException e) {
            // 풀지 못해도 ttl 뒤에 만료된다.
            LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 소유 리스를 풀지 못했다", e);
        }
    }

    @Override
    public void requestHandoff(UUID docId) {
        try {
            redis.convertAndSend(HANDOFF_CHANNEL, docId.toString());
        } catch (DataAccessException e) {
            LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 를 넘겨 달라고 알리지 못했다", e);
        }
    }

    static String key(UUID docId) {
        return "copage:doc:" + docId + ":owner";
    }
}
