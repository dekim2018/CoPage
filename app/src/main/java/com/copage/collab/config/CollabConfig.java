package com.copage.collab.config;

import com.copage.collab.app.ChangePolicy;
import com.copage.collab.app.CollabMetrics;
import com.copage.collab.app.DocumentSession;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.TicketService;
import com.copage.collab.app.TicketStore;
import com.copage.collab.app.UserNames;
import com.copage.collab.domain.PageRole;
import com.copage.collab.in.CollabWebSocketHandler;
import com.copage.collab.in.ProtocolCodec;
import com.copage.collab.out.JdbcDocumentStore;
import com.copage.collab.out.MicrometerCollabMetrics;
import com.copage.collab.out.RedisTicketStore;
import com.copage.identity.app.AuthException;
import com.copage.identity.app.AuthService;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PermissionChangeListener;
import com.copage.workspace.app.WorkspaceService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
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
     * 권한이 바뀌면 열린 연결에 새 등급을 적용한다 (docs/07-SECURITY.md 9.4, S-5, D-17). 알림은 권한을 바꾼 요청 스레드에서
     * 오지만, 여기서는 열린 세션을 훑어 일을 넘기기만 하고 저장소 읽기는 세션이 background 에서 한다.
     */
    @Bean
    PermissionChangeListener liveRoleUpdater(DocumentSessions documentSessions, PagePermissions pagePermissions) {
        DocumentSession.RoleResolver resolver = (userId, docId) ->
                pagePermissions.access(userId, docId) instanceof Access.Granted granted
                        ? Optional.of(PageRole.valueOf(granted.level().name()))
                        : Optional.empty();
        return change -> documentSessions.reauthorize(
                docId -> change.pageIds() == null || change.pageIds().contains(docId),
                userId -> change.userId() == null || change.userId().equals(userId),
                resolver);
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
            Clock collabClock,
            WorkspaceService workspaceService,
            CollabMetrics collabMetrics) {
        DocumentSession.Settings settings = new DocumentSession.Settings(
                properties.maxConnectionsPerDocument(),
                properties.rebaseMaxGap(),
                properties.rebaseMaxAge(),
                properties.sessionUnloadDelay(),
                properties.snapshotInterval(),
                properties.historyWindow());
        return new DocumentSessions(
                documentStore,
                new ChangePolicy(properties.maxBlocks(), properties.maxTextLength()),
                settings,
                collabExecutor,
                collabExecutor,
                (task, delay) -> collabScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS),
                collabClock,
                // 문서 제목은 페이지 목록과 경로가 보인다. 저장은 workspace 모듈이 한다 (D-21).
                workspaceService::updateTitle,
                collabMetrics);
    }

    @Bean
    CollabWebSocketHandler collabWebSocketHandler(
            DocumentSessions documentSessions,
            JsonMapper jsonMapper,
            ScheduledExecutorService collabScheduler,
            ExecutorService collabExecutor,
            Clock collabClock,
            CollabMetrics collabMetrics,
            MeterRegistry meterRegistry) {
        CollabWebSocketHandler handler = new CollabWebSocketHandler(
                documentSessions,
                new ProtocolCodec(jsonMapper),
                new CollabWebSocketHandler.Limits(
                        properties.maxMessageBytes(),
                        properties.maxChangesPerSecond(),
                        properties.maxPresencePerSecond(),
                        properties.idleTimeout(),
                        properties.sendTimeLimit(),
                        properties.sendBufferBytes()),
                collabClock,
                collabExecutor,
                collabMetrics);
        collabScheduler.scheduleWithFixedDelay(handler::closeIdleConnections, 5, 5, TimeUnit.SECONDS);
        // 노드 수용량 (13.4). 열린 세션은 문서 수, 연결은 WebSocket 수다.
        Gauge.builder("collab.sessions", documentSessions, DocumentSessions::size)
                .description("메모리에 올라온 문서 세션 수")
                .register(meterRegistry);
        Gauge.builder("collab.connections", handler, CollabWebSocketHandler::connectionCount)
                .description("열린 WebSocket 연결 수")
                .register(meterRegistry);
        return handler;
    }

    @Bean
    CollabMetrics collabMetrics(MeterRegistry meterRegistry) {
        return new MicrometerCollabMetrics(meterRegistry);
    }

    /**
     * 컨테이너의 글 메시지 버퍼. 한도는 바이트로 재는데 버퍼는 글자(char) 수다. UTF-8 에서 글자 하나는 1 바이트 이상이므로
     * 한도 안의 메시지는 언제나 한도 글자 수의 버퍼에 들어가고, 바이트가 한도를 넘는 메시지(예: 한글 3만 자)는 처리기까지 와서
     * 4008 로 끊긴다. 글자 수까지 한도를 넘는 메시지는 컨테이너가 1009 로 끊고, 클라이언트는 1009 도 재접속하지 않는다.
     *
     * <p>컨테이너는 이 버퍼를 연결마다 미리 잡는다(char 2 바이트). 한도의 4배(256K 글자, 연결당 약 512KB)로 두었을 때 힙 1GB
     * 에서 연결 약 930개에 메모리가 넘쳤다(부하 시험, docs/PERFORMANCE-REVIEW.md). 한도와 같게 두면 연결당 약 128KB 다.
     */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.maxMessageBytes());
        return container;
    }
}
