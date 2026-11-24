package com.copage.notification.config;

import com.copage.notification.app.NotificationService;
import com.copage.notification.in.LiveStreams;
import com.copage.notification.out.JdbcNotificationRepository;
import com.copage.notification.out.RedisLiveEvents;
import java.time.Clock;
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
