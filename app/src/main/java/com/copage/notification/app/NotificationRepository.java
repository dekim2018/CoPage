package com.copage.notification.app;

import com.copage.notification.domain.Notification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 알림 저장소 포트. */
public interface NotificationRepository {

    /** 넣는다. dedupeKey 가 이미 있으면 넣지 않고 비어 있다. */
    Optional<Notification> insert(Notification notification, String dedupeKey);

    /** 최근부터. */
    List<Notification> recent(UUID userId, int limit);

    long unread(UUID userId);

    /** 읽음으로 표시한다. ids 가 비면 모두. */
    void markRead(UUID userId, List<UUID> ids);
}
