package com.copage.workspace.config;

import com.copage.identity.app.UserDirectory;
import com.copage.notification.app.NotificationService;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.WorkspaceMembership;
import com.copage.workspace.app.InviteMailer;
import com.copage.workspace.app.InviteService;
import com.copage.workspace.app.TreeEvents;
import com.copage.workspace.app.WorkspaceRepository;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.domain.WorkspaceRole;
import com.copage.workspace.out.JdbcInviteRepository;
import com.copage.workspace.out.JdbcWorkspaceRepository;
import com.copage.workspace.out.SmtpInviteMailer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WorkspaceConfig {

    private static final System.Logger LOG = System.getLogger(WorkspaceConfig.class.getName());

    /** 휴지통에 남겨 두는 기간 (W-4). 요구사항의 값이라 설정으로 빼지 않았다. */
    static final Duration TRASH_KEEP = Duration.ofDays(30);

    @Bean
    JdbcWorkspaceRepository workspaceRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        return new JdbcWorkspaceRepository(jdbcClient, transactionTemplate);
    }

    /** permission 모듈이 페이지 권한을 줄 때 멤버가 아닌 사용자를 GUEST 로 넣는다 (D-13). */
    @Bean
    WorkspaceMembership workspaceMembership(JdbcWorkspaceRepository workspaceRepository) {
        return (workspaceId, userId) -> workspaceRepository.addMember(workspaceId, userId, WorkspaceRole.GUEST);
    }

    @Bean
    JdbcInviteRepository inviteRepository(JdbcClient jdbcClient) {
        return new JdbcInviteRepository(jdbcClient);
    }

    /**
     * 초대 메일 (W-1, D-50). 메일 서버(SPRING_MAIL_HOST)와 화면 주소(COPAGE_PUBLIC_URL)가 둘 다 있어야 보낸다. 하나라도 없으면
     * 보내지 않고 OWNER 가 링크를 직접 건넨다(D-40).
     */
    @Bean
    InviteMailer inviteMailer(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${copage.mail.from:}") String from,
            @Value("${copage.mail.public-url:}") String publicUrl) {
        if (mailHost.isBlank()) {
            return InviteMailer.NONE;
        }
        if (publicUrl.isBlank() || from.isBlank()) {
            LOG.log(System.Logger.Level.WARNING,
                    "메일 서버는 있지만 COPAGE_PUBLIC_URL 이나 COPAGE_MAIL_FROM 이 없어 초대 메일을 보내지 않는다");
            return InviteMailer.NONE;
        }
        return new SmtpInviteMailer(mailSender.getObject(), from, publicUrl,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    InviteService inviteService(
            JdbcInviteRepository inviteRepository,
            JdbcWorkspaceRepository workspaceRepository,
            UserDirectory userDirectory,
            PageGrants pageGrants,
            TransactionTemplate transactionTemplate,
            InviteMailer inviteMailer) {
        InviteService service = new InviteService(
                inviteRepository,
                workspaceRepository,
                userDirectory,
                pageGrants,
                work -> transactionTemplate.executeWithoutResult(status -> work.run()),
                Clock.systemUTC());
        service.mailerIs(inviteMailer);
        return service;
    }

    /**
     * 트리가 바뀌면 그 워크스페이스 멤버들의 열린 알림 연결에 tree 이벤트를 보낸다 (W-5, D-46). 볼 수 없는 페이지의 제목은 싣지
     * 않는다. 사이드바는 부모 ID 만 받고 그 목록을 자기 권한으로 다시 읽는다.
     */
    @Bean
    TreeEvents treeEvents(
            WorkspaceService workspaceService,
            JdbcWorkspaceRepository workspaceRepository,
            NotificationService notificationService) {
        TreeEvents events = (workspaceId, parentIds) -> notificationService.broadcast(
                workspaceRepository.members(workspaceId).stream().map(WorkspaceRepository.Member::userId).toList(),
                "tree",
                Map.of("workspaceId", workspaceId.toString(),
                        "parentIds", parentIds.stream().map(UUID::toString).toList()));
        workspaceService.treeEventsTo(events);
        return events;
    }

    @Bean
    WorkspaceService workspaceService(
            JdbcWorkspaceRepository workspaceRepository,
            UserDirectory userDirectory,
            PagePermissions pagePermissions,
            PageGrants pageGrants,
            TransactionTemplate transactionTemplate) {
        return new WorkspaceService(
                workspaceRepository,
                userDirectory,
                pagePermissions,
                pageGrants,
                work -> transactionTemplate.executeWithoutResult(status -> work.run()),
                Clock.systemUTC());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService workspaceJobs() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("workspace-jobs").daemon().factory());
    }

    /**
     * 한 시간마다 휴지통에서 30일이 지난 페이지와 만료된 초대를 지운다 (W-4, D-47). 모든 노드가 돌려도 같은 행을 지울 뿐이다.
     */
    @Bean
    ScheduledFuture<?> trashPurgeJob(
            WorkspaceService workspaceService, InviteService inviteService, ScheduledExecutorService workspaceJobs) {
        return workspaceJobs.scheduleWithFixedDelay(() -> {
            try {
                int pages = workspaceService.purgeTrash(TRASH_KEEP);
                int invites = inviteService.purgeExpired();
                if (pages > 0 || invites > 0) {
                    LOG.log(System.Logger.Level.INFO, "휴지통 페이지 " + pages + " 개와 만료된 초대 " + invites + " 개를 지웠다");
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "휴지통을 비우지 못했다", e);
            }
        }, 1, 60, TimeUnit.MINUTES);
    }
}
