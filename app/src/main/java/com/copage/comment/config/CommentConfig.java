package com.copage.comment.config;

import com.copage.collab.app.BodySink;
import com.copage.collab.app.DocEvents;
import com.copage.collab.app.DocumentSessions;
import com.copage.comment.app.CommentService;
import com.copage.comment.out.JdbcCommentRepository;
import com.copage.notification.app.NotificationService;
import com.copage.permission.app.PagePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 댓글 모듈을 잇는다 (D-46). 댓글이 바뀌면 협업 모듈이 그 문서를 연 연결들에 알린다. */
@Configuration
public class CommentConfig {

    private static final System.Logger LOG = System.getLogger(CommentConfig.class.getName());

    @Bean
    CommentService commentService(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            PagePermissions pagePermissions,
            NotificationService notificationService,
            DocEvents docEvents) {
        return new CommentService(
                new JdbcCommentRepository(jdbcClient, transactionTemplate),
                pagePermissions,
                notificationService,
                docEvents::commentChanged,
                Clock.systemUTC());
    }

    /** 본문에 남은 댓글 서식을 받아 원문이 지워진 스레드를 표시한다 (D-50). */
    @Bean
    BodySink commentAnchors(DocumentSessions documentSessions, CommentService commentService) {
        BodySink sink = new BodySink() {
            @Override
            public void update(UUID docId, String body, long rev) {
                // 본문은 검색이 쓴다. 댓글은 스레드 목록이 바뀔 때만 본다.
            }

            @Override
            public void commentThreads(UUID docId, Set<String> threads) {
                commentService.anchorsSeen(docId, threads);
            }

            @Override
            public void commentMarked(UUID docId, Set<String> commentThreads) {
                commentService.marked(docId, commentThreads);
            }
        };
        documentSessions.addBodySink(sink);
        return sink;
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService commentJobs() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("comment-jobs").daemon().factory());
    }

    /**
     * 한 시간마다 본문에 서식이 한 번도 붙지 않은 하루 넘은 스레드를 지운다 (10.2, D-50). 설계는 10분이었다. 서식은 다음에 세션을
     * 올릴 때 다시 적히므로, 서버가 서식을 적기 전에 내려가도 지우지 않게 넉넉히 둔다.
     */
    @Bean
    ScheduledFuture<?> unanchoredThreadJob(CommentService commentService, ScheduledExecutorService commentJobs) {
        return commentJobs.scheduleWithFixedDelay(() -> {
            try {
                commentService.purgeUnanchored(Duration.ofDays(1));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "위치 없는 댓글 스레드를 지우지 못했다", e);
            }
        }, 5, 60, TimeUnit.MINUTES);
    }
}
