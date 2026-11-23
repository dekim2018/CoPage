package com.copage.workspace.config;

import com.copage.identity.app.UserDirectory;
import com.copage.workspace.app.WorkspaceService;
import com.copage.workspace.out.JdbcWorkspaceRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WorkspaceConfig {

    @Bean
    WorkspaceService workspaceService(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, UserDirectory userDirectory) {
        return new WorkspaceService(
                new JdbcWorkspaceRepository(jdbcClient, transactionTemplate), userDirectory, Clock.systemUTC());
    }
}
