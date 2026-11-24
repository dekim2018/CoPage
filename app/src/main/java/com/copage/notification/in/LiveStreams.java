package com.copage.notification.in;

import com.copage.notification.out.RedisLiveEvents;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

/**
 * 이 노드의 열린 알림 연결(SSE). 사용자마다 여러 개(탭마다)일 수 있다. 보내다 실패한 연결은 뺀다. 프록시가 조용한 연결을 끊지
 * 않게 주기적으로 빈 줄을 보낸다. 끝낼 때는 웹 서버의 우아한 종료보다 먼저 연결을 닫는다. 열린 SSE 가 요청으로 남아 종료를
 * 기다리게 하지 않는다. 화면은 다른 노드로 다시 붙는다.
 */
public final class LiveStreams implements RedisLiveEvents.LocalDelivery, SmartLifecycle {

    /** 연결 하나의 최대 길이. 화면이 끊기면 다시 붙는다. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService heartbeats;

    @Override
    public synchronized void start() {
        if (heartbeats != null) {
            return;
        }
        heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("live-heartbeat").daemon().factory());
        heartbeats.scheduleWithFixedDelay(this::heartbeat, 25, 25, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        if (heartbeats != null) {
            heartbeats.shutdownNow();
            heartbeats = null;
        }
        streams.values().forEach(mine -> mine.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                // 이미 끊긴 연결이다.
            }
        }));
        streams.clear();
    }

    @Override
    public boolean isRunning() {
        return heartbeats != null;
    }

    /** 가장 먼저 멈춘다. 웹 서버의 우아한 종료(DEFAULT_PHASE - 1024)보다 앞이다. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> mine = streams.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet());
        mine.add(emitter);
        Runnable remove = () -> mine.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @Override
    public void deliver(UUID userId, String type, JsonNode data) {
        Set<SseEmitter> mine = streams.get(userId);
        if (mine == null) {
            return;
        }
        for (SseEmitter emitter : mine) {
            try {
                emitter.send(SseEmitter.event().name(type).data(data.toString()));
            } catch (IOException | IllegalStateException e) {
                mine.remove(emitter);
            }
        }
    }

    /** 조용한 연결을 프록시가 끊지 않게 주석 줄을 보낸다. */
    public void heartbeat() {
        streams.values().forEach(mine -> mine.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                mine.remove(emitter);
            }
        }));
    }

    int size() {
        return streams.values().stream().mapToInt(Set::size).sum();
    }
}
