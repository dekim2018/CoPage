package com.copage.collab.config;

import com.copage.collab.app.ChangePolicy;
import com.copage.collab.app.DocumentSession;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.TicketService;
import com.copage.collab.app.TicketStore;
import com.copage.collab.app.UserNames;
import com.copage.collab.in.CollabWebSocketHandler;
import com.copage.collab.in.ProtocolCodec;
import com.copage.collab.out.JdbcDocumentStore;
import com.copage.identity.app.AuthException;
import com.copage.identity.app.AuthService;
import com.copage.collab.out.RedisTicketStore;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import tools.jackson.databind.json.JsonMapper;

/** 협업 모듈의 빈. WebSocket 경로 등록은 in 패키지의 CollabWebSocketSetup 이 한다. */
@Configuration
@EnableConfigurationProperties(CollabProperties.class)
public class CollabConfig {

    private final CollabProperties properties;

    CollabConfig(CollabProperties properties) {
        this.properties = properties;
    }

    @Bean
    Clock collabClock() {
        return Clock.systemUTC();
    }

    /** 문서 세션의 일과 연결마다의 전송 작업을 돌리는 곳. 세션마다 직렬 실행기를 이 위에 얹는다. */
    @Bean(destroyMethod = "close")
    ExecutorService collabExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 세션 내리기와 무응답 연결 정리를 예약한다. 예약된 일은 곧바로 세션의 실행기로 넘기므로 스레드 하나면 된다. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService collabScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("collab-scheduler").daemon().factory());
    }

    @Bean
    DocumentStore documentStore(JdbcClient jdbcClient, TransactionTemplate transactionTemplate, JsonMapper jsonMapper) {
        return new JdbcDocumentStore(jdbcClient, transactionTemplate, jsonMapper, properties.snapshotsKept());
    }

    @Bean
    TicketStore ticketStore(StringRedisTemplate redis) {
        return new RedisTicketStore(redis);
    }

    /**
     * 협업 모듈이 사용자 이름을 물을 때 계정 모듈에 넘긴다. 모듈끼리는 이 설정에서만 이어진다. 사용자를 못 찾으면(지워졌거나
     * 테스트가 바로 만든 티켓) 이름 없음으로 둔다.
     */
    @Bean
    UserNames userNames(AuthService authService) {
        return userId -> {
            try {
                return authService.me(userId).name();
            } catch (AuthException e) {
                return "이름 없음";
            }
        };
    }

    @Bean
    TicketService ticketService(TicketStore ticketStore) {
        return new TicketService(ticketStore, properties.ticketTtl());
    }

    @Bean
    DocumentSessions documentSessions(
            DocumentStore documentStore,
            ExecutorService collabExecutor,
            ScheduledExecutorService collabScheduler,
            Clock collabClock) {
        DocumentSession.Settings settings = new DocumentSession.Settings(
                properties.maxConnectionsPerDocument(),
                properties.rebaseMaxGap(),
                properties.rebaseMaxAge(),
                properties.sessionUnloadDelay(),
                properties.snapshotInterval());
        return new DocumentSessions(
                documentStore,
                new ChangePolicy(properties.maxBlocks(), properties.maxTextLength()),
                settings,
                collabExecutor,
                collabExecutor,
                (task, delay) -> collabScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS),
                collabClock);
    }

    @Bean
    CollabWebSocketHandler collabWebSocketHandler(
            DocumentSessions documentSessions,
            JsonMapper jsonMapper,
            ScheduledExecutorService collabScheduler,
            ExecutorService collabExecutor,
            Clock collabClock) {
        CollabWebSocketHandler handler = new CollabWebSocketHandler(
                documentSessions,
                new ProtocolCodec(jsonMapper),
                new CollabWebSocketHandler.Limits(
                        properties.maxMessageBytes(),
                        properties.maxChangesPerSecond(),
                        properties.idleTimeout(),
                        properties.sendTimeLimit(),
                        properties.sendBufferBytes()),
                collabClock,
                collabExecutor);
        collabScheduler.scheduleWithFixedDelay(handler::closeIdleConnections, 5, 5, TimeUnit.SECONDS);
        return handler;
    }

    /** 크기 한도를 넘는 메시지도 일단 받아서 4008 로 끊는다. 컨테이너 버퍼가 작으면 컨테이너가 1009 로 먼저 끊어 버린다. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.maxMessageBytes() * 4);
        return container;
    }
}
