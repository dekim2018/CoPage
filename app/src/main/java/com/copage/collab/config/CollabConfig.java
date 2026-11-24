package com.copage.collab.config;

import com.copage.collab.app.ChangePolicy;
import com.copage.collab.app.CollabMetrics;
import com.copage.collab.app.DocumentSession;
import com.copage.collab.app.DocumentSessions;
import com.copage.collab.app.DocumentStore;
import com.copage.collab.app.OwnershipLease;
import com.copage.collab.app.TicketService;
import com.copage.collab.app.TicketStore;
import com.copage.collab.app.UserNames;
import com.copage.collab.domain.PageRole;
import com.copage.collab.in.CollabWebSocketHandler;
import com.copage.collab.in.ProtocolCodec;
import com.copage.collab.out.JdbcDocumentStore;
import com.copage.collab.out.MicrometerCollabMetrics;
import com.copage.collab.out.RedisOwnershipLease;
import com.copage.collab.out.RedisTicketStore;
import com.copage.identity.app.AuthException;
import com.copage.identity.app.AuthService;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PermissionChangeListener;
import com.copage.permission.app.ShareLinks;
import com.copage.permission.domain.PageLevel;
import com.copage.web.NodeIdentity;
import com.copage.workspace.app.WorkspaceService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import tools.jackson.databind.json.JsonMapper;

/** 협업 모듈의 빈. WebSocket 경로 등록은 in 패키지의 CollabWebSocketSetup 이 한다. */
@Configuration
@EnableConfigurationProperties(CollabProperties.class)
public class CollabConfig {

    private static final System.Logger LOG = System.getLogger(CollabConfig.class.getName());

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
    PermissionChangeListener liveRoleUpdater(
            DocumentSessions documentSessions, PagePermissions pagePermissions, ShareLinks shareLinks) {
        // 공유 링크로 들어온 연결은 자기 등급과 링크 등급 중 높은 것이다. 링크가 꺼지거나 새로 만들어졌으면 링크 몫은 없다 (D-41).
        DocumentSession.RoleResolver resolver = (principal, docId) -> {
            PageLevel own = pagePermissions.access(principal.userId(), docId) instanceof Access.Granted granted
                    ? granted.level()
                    : null;
            PageLevel viaLink = principal.shareLinkId() == null
                    ? null
                    : shareLinks.levelOf(principal.shareLinkId(), docId).orElse(null);
            PageLevel level = PageLevel.max(own, viaLink);
            return level == null ? Optional.empty() : Optional.of(PageRole.valueOf(level.name()));
        };
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
            CollabMetrics collabMetrics,
            OwnershipLease ownershipLease) {
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
                collabMetrics,
                ownershipLease,
                properties.handoffGrace());
    }

    @Bean
    OwnershipLease ownershipLease(StringRedisTemplate redis, NodeIdentity node) {
        return new RedisOwnershipLease(redis, node.id(), properties.leaseTtl());
    }

    /** 쥔 리스를 주기적으로 이어 쥐고, 다른 노드의 넘겨 달라는 요청을 듣는다 (5.3, 5.4, D-43). */
    @Bean
    RedisMessageListenerContainer handoffListener(
            RedisConnectionFactory connectionFactory,
            DocumentSessions documentSessions,
            ScheduledExecutorService collabScheduler) {
        long renew = properties.leaseRenewInterval().toMillis();
        collabScheduler.scheduleWithFixedDelay(() -> {
            try {
                documentSessions.renewLeases();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "소유 리스를 이어 쥐지 못했다", e);
            }
        }, renew, renew, TimeUnit.MILLISECONDS);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                documentSessions.handOffRequested(
                        UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "넘겨 달라는 요청의 문서 ID 가 틀렸다", e);
            }
        }, new ChannelTopic(RedisOwnershipLease.HANDOFF_CHANNEL));
        return container;
    }

    /**
     * 노드를 내릴 때 새 연결보다 먼저 세션을 다른 노드에 넘긴다(5.4 의 "계획해서 내림"). 클라이언트는 4009 를 받고 다른 노드로
     * 다시 붙는다. 스냅샷을 남길 시간을 조금 준다.
     */
    @Bean
    SmartLifecycle sessionHandoffOnShutdown(DocumentSessions documentSessions) {
        return new SmartLifecycle() {
            private volatile boolean running;

            // 스프링은 내리지 않고 멈췄다 다시 시작할 수도 있다(시험 컨텍스트를 쉬게 할 때). 다시 시작하면 연결을 받는다.
            @Override
            public void start() {
                documentSessions.acceptJoins();
                running = true;
            }

            @Override
            public void stop() {
                documentSessions.handOffAll();
                long deadline = System.currentTimeMillis() + 3_000;
                while (documentSessions.size() > 0 && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            // 웹 서버(우아한 종료)보다 먼저 멈춘다.
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE;
            }
        };
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
     * 에서 연결 약 930개에 메모리가 넘쳤다(부하 시험, docs/review/PERFORMANCE-REVIEW.md, D-36). 한도와 같게 두면 연결당 약 128KB 다.
     */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.maxMessageBytes());
        return container;
    }
}
