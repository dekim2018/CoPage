package com.copage.permission.config;

import com.copage.identity.app.UserDirectory;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PermissionChangeListener;
import com.copage.permission.app.PermissionChanges;
import com.copage.permission.app.PermissionRepository;
import com.copage.permission.app.WorkspaceMembership;
import com.copage.permission.out.JdbcGrantRepository;
import com.copage.permission.out.JdbcPermissionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class PermissionConfig {

    @Bean
    PermissionRepository permissionRepository(JdbcClient jdbcClient) {
        return new JdbcPermissionRepository(jdbcClient);
    }

    @Bean
    PagePermissions pagePermissions(PermissionRepository permissionRepository) {
        return new PagePermissions(permissionRepository);
    }

    /** 듣는 쪽은 알릴 때마다 찾는다. collab 의 리스너가 이 빈보다 늦게 만들어져도 된다. */
    @Bean
    PermissionChanges permissionChanges(ObjectProvider<PermissionChangeListener> listeners) {
        return new PermissionChanges(() -> listeners.orderedStream().toList());
    }

    @Bean
    PageGrants pageGrants(
            JdbcClient jdbcClient,
            PagePermissions pagePermissions,
            PermissionRepository permissionRepository,
            WorkspaceMembership workspaceMembership,
            UserDirectory userDirectory,
            PermissionChanges permissionChanges) {
        return new PageGrants(
                pagePermissions,
                permissionRepository,
                new JdbcGrantRepository(jdbcClient),
                workspaceMembership,
                userDirectory,
                permissionChanges);
    }
}
