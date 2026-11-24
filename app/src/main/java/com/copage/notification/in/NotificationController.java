package com.copage.notification.in;

import com.copage.notification.app.NotificationService;
import com.copage.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 알림 (docs/09-API.md, C-4, W-5, D-46). 목록과 읽음 표시, 그리고 실시간 연결(SSE). 화면은 EventSource 대신 fetch 로 연결해
 * Authorization 헤더를 싣는다.
 */
@RestController
class NotificationController {

    record Item(UUID id, Notification.Kind kind, UUID docId, String actorName, String preview, Instant createdAt,
            boolean read) {
        static Item of(Notification n) {
            return new Item(n.id(), n.kind(), n.docId(), n.actorName(), n.preview(), n.createdAt(), n.readAt() != null);
        }
    }

    record Inbox(long unread, List<Item> items) {
    }

    /** @param ids 비우면 모두 */
    record ReadRequest(List<UUID> ids) {
    }

    private final NotificationService notifications;
    private final LiveStreams streams;

    NotificationController(NotificationService notifications, LiveStreams streams) {
        this.notifications = notifications;
        this.streams = streams;
    }

    @GetMapping("/api/notifications")
    Inbox inbox(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        return new Inbox(notifications.unread(userId), notifications.recent(userId).stream().map(Item::of).toList());
    }

    @PostMapping("/api/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Jwt jwt, @RequestBody ReadRequest request) {
        notifications.markRead(userId(jwt), request.ids() == null ? List.of() : request.ids());
    }

    @GetMapping(path = "/api/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return streams.open(userId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
