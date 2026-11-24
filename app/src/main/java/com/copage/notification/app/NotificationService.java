package com.copage.notification.app;

import com.copage.notification.domain.Notification;
import com.copage.notification.domain.Notification.Kind;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 알림 (docs/08-FEATURES.md 10.3, C-4, D-46). 저장하고, 받는 사람의 열린 알림 연결에 바로 보낸다. 같은 dedupeKey 는 한 번만
 * 보낸다. 사이드바 트리 변경(W-5)처럼 저장하지 않는 실시간 이벤트도 같은 연결로 보낸다.
 */
public final class NotificationService {

    private static final int PREVIEW_LENGTH = 120;
    private static final int LIST_SIZE = 50;

    private final NotificationRepository repository;
    private final LiveEvents live;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, LiveEvents live, Clock clock) {
        this.repository = repository;
        this.live = live;
        this.clock = clock;
    }

    /** 자기 자신에게는 보내지 않는다. */
    public void notify(UUID userId, Kind kind, UUID docId, UUID actorId, String preview, String dedupeKey) {
        if (userId.equals(actorId)) {
            return;
        }
        String shortPreview = preview.length() > PREVIEW_LENGTH ? preview.substring(0, PREVIEW_LENGTH) + "…" : preview;
        Notification notification = new Notification(UUID.randomUUID(), userId, kind, docId, actorId, null, shortPreview,
                clock.instant().truncatedTo(ChronoUnit.MICROS), null);
        repository.insert(notification, dedupeKey).ifPresent(saved -> {
            Map<String, Object> data = new HashMap<>();
            data.put("id", saved.id().toString());
            data.put("kind", saved.kind().name());
            data.put("docId", String.valueOf(saved.docId()));
            data.put("preview", saved.preview());
            live.send(userId, "notification", data);
        });
    }

    /** dedupeKey 가 prefix 로 시작하는 알림을 지운다. 지운 댓글의 글이 알림 미리보기에 남지 않게 한다 (D-49). */
    public void forget(String prefix) {
        repository.deleteByDedupePrefix(prefix);
    }

    public List<Notification> recent(UUID userId) {
        return repository.recent(userId, LIST_SIZE);
    }

    public long unread(UUID userId) {
        return repository.unread(userId);
    }

    public void markRead(UUID userId, List<UUID> ids) {
        repository.markRead(userId, ids);
    }

    /** 저장하지 않는 실시간 이벤트를 여러 사람에게 보낸다. */
    public void broadcast(Collection<UUID> userIds, String type, Map<String, Object> data) {
        userIds.forEach(userId -> live.send(userId, type, data));
    }
}
