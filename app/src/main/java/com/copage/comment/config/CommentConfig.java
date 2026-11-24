package com.copage.comment.config;

import com.copage.collab.app.DocEvents;
import com.copage.comment.app.CommentService;
import com.copage.comment.out.JdbcCommentRepository;
import com.copage.notification.app.NotificationService;
import com.copage.permission.app.PagePermissions;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 댓글 모듈을 잇는다 (D-46). 댓글이 바뀌면 협업 모듈이 그 문서를 연 연결들에 알린다. */
@Configuration
public class CommentConfig {

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
}
