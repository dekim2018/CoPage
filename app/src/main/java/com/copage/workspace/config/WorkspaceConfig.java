package com.copage.workspace.config;

import com.copage.identity.app.UserDirectory;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.WorkspaceMembership;
import com.copage.workspace.app.InviteService;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.domain.WorkspaceRole;
import com.copage.workspace.out.JdbcInviteRepository;
import com.copage.workspace.out.JdbcWorkspaceRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WorkspaceConfig {

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

    @Bean
    InviteService inviteService(
            JdbcInviteRepository inviteRepository,
            JdbcWorkspaceRepository workspaceRepository,
            UserDirectory userDirectory,
            PageGrants pageGrants,
            TransactionTemplate transactionTemplate) {
        return new InviteService(
                inviteRepository,
                workspaceRepository,
                userDirectory,
                pageGrants,
                work -> transactionTemplate.executeWithoutResult(status -> work.run()),
                Clock.systemUTC());
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
}
