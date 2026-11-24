package com.copage.notification.config;

import com.copage.notification.app.NotificationService;
import com.copage.notification.domain.Notification;
import com.copage.notification.in.LiveStreams;
import com.copage.notification.out.JdbcNotificationRepository;
import com.copage.notification.out.RedisLiveEvents;
import com.copage.permission.app.GrantNotices;
import com.copage.permission.app.PageGrants;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** 알림 모듈을 잇는다 (D-46). */
@Configuration
public class NotificationConfig {

    private static final Map<String, String> LEVELS =
            Map.of("VIEW", "보기", "COMMENT", "댓글", "EDIT", "편집", "FULL", "전체");

    /**
     * 페이지 권한을 받으면 알린다 (10.3 의 페이지 초대, D-50). 같은 페이지의 같은 등급은 한 번만. 등급이 바뀌면 다시 알린다.
     * 자기에게 준 것은 알리지 않는다(NotificationService).
     */
    @Bean
    GrantNotices pageShareNotices(PageGrants pageGrants, NotificationService notificationService) {
        GrantNotices notices = (actor, pageId, userId, level) -> notificationService.notify(
                userId, Notification.Kind.PAGE_SHARED, pageId, actor,
                LEVELS.getOrDefault(level.name(), level.name()) + " 권한을 받았다",
                "share:" + pageId + ":" + userId + ":" + level.name());
        pageGrants.grantNoticesTo(notices);
        return notices;
    }

    /** 열린 알림 연결에 25초마다 빈 줄을 보낸다(프록시가 조용한 연결을 끊지 않게). */
    @Bean
    LiveStreams liveStreams() {
        return new LiveStreams();
    }

    @Bean
    RedisLiveEvents liveEvents(StringRedisTemplate redis, JsonMapper jsonMapper, LiveStreams liveStreams) {
        return new RedisLiveEvents(redis, jsonMapper, liveStreams);
    }

    @Bean
    RedisMessageListenerContainer liveEventsListener(RedisConnectionFactory connectionFactory, RedisLiveEvents liveEvents) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(liveEvents, new ChannelTopic(RedisLiveEvents.CHANNEL));
        return container;
    }

    @Bean
    NotificationService notificationService(JdbcClient jdbcClient, RedisLiveEvents liveEvents) {
        return new NotificationService(new JdbcNotificationRepository(jdbcClient), liveEvents, Clock.systemUTC());
    }
}
