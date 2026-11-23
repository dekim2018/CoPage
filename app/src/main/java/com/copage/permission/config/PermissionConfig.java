package com.copage.permission.config;

import com.copage.permission.app.PagePermissions;
import com.copage.permission.out.JdbcPermissionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class PermissionConfig {

    @Bean
    PagePermissions pagePermissions(JdbcClient jdbcClient) {
        return new PagePermissions(new JdbcPermissionRepository(jdbcClient));
    }
}
