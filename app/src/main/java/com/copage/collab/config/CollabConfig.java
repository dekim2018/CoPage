package com.copage.collab.config;

import com.copage.collab.app.ChangePolicy;
import com.copage.collab.app.DocumentSession;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.TicketService;
import com.copage.collab.app.TicketStore;
import com.copage.collab.in.CollabWebSocketHandler;
import com.copage.collab.in.ProtocolCodec;
import com.copage.collab.out.InMemoryDocumentStore;
import com.copage.collab.out.InMemoryTicketStore;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    /** 문서 세션의 일을 실제로 돌리는 곳. 세션마다 직렬 실행기를 이 위에 얹는다. */
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
    DocumentStore documentStore() {
        return new InMemoryDocumentStore();
    }

    @Bean
    TicketStore ticketStore(Clock collabClock) {
        return new InMemoryTicketStore(collabClock);
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
                properties.sessionUnloadDelay());
        return new DocumentSessions(
                documentStore,
                new ChangePolicy(properties.maxBlocks(), properties.maxTextLength()),
                settings,
                collabExecutor,
                (task, delay) -> collabScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS),
                collabClock);
    }

    @Bean
    CollabWebSocketHandler collabWebSocketHandler(
            DocumentSessions documentSessions,
            JsonMapper jsonMapper,
            ScheduledExecutorService collabScheduler,
            Clock collabClock) {
        CollabWebSocketHandler handler = new CollabWebSocketHandler(
                documentSessions,
                new ProtocolCodec(jsonMapper),
                new CollabWebSocketHandler.Limits(
                        properties.maxMessageBytes(),
                        properties.maxChangesPerSecond(),
                        properties.idleTimeout(),
                        (int) properties.sendTimeLimit().toMillis(),
                        properties.sendBufferBytes()),
                collabClock);
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
