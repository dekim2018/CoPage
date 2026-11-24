package com.copage.permission.config;

import com.copage.identity.app.UserDirectory;
import com.copage.permission.app.PageGrants;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PermissionChange;
import com.copage.permission.app.PermissionChangeListener;
import com.copage.permission.app.PermissionChanges;
import com.copage.permission.app.PermissionRepository;
import com.copage.permission.app.ShareLinks;
import com.copage.permission.app.WorkspaceMembership;
import com.copage.permission.out.JdbcGrantRepository;
import com.copage.permission.out.JdbcPermissionRepository;
import com.copage.permission.out.JdbcShareLinkRepository;
import com.copage.permission.out.RedisPermissionRelay;
import com.copage.web.NodeIdentity;
import java.time.Clock;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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

    /** 다른 노드에 권한 변경을 전하고, 다른 노드의 변경을 이 노드의 세션에 알린다 (5.5, D-43). */
    @Bean
    RedisPermissionRelay permissionRelay(
            StringRedisTemplate redis, JsonMapper jsonMapper, NodeIdentity node, PermissionChanges permissionChanges) {
        return new RedisPermissionRelay(redis, jsonMapper, node.id(), permissionChanges);
    }

    @Bean
    RedisMessageListenerContainer permissionRelayListener(
            RedisConnectionFactory connectionFactory, RedisPermissionRelay permissionRelay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(permissionRelay, new ChannelTopic(RedisPermissionRelay.CHANNEL));
        return container;
    }

    /** 권한을 바꾸는 쪽이 부른다. 이 노드의 세션에 바로 알리고 다른 노드에도 전한다. */
    private static Consumer<PermissionChange> everywhere(
            PermissionChanges permissionChanges, RedisPermissionRelay permissionRelay) {
        return change -> {
            permissionChanges.accept(change);
            permissionRelay.publish(change);
        };
    }

    @Bean
    ShareLinks shareLinks(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            PagePermissions pagePermissions,
            PermissionChanges permissionChanges,
            RedisPermissionRelay permissionRelay) {
        return new ShareLinks(new JdbcShareLinkRepository(jdbcClient, transactionTemplate), pagePermissions,
                everywhere(permissionChanges, permissionRelay), Clock.systemUTC());
    }

    @Bean
    PageGrants pageGrants(
            JdbcClient jdbcClient,
            PagePermissions pagePermissions,
            PermissionRepository permissionRepository,
            WorkspaceMembership workspaceMembership,
            UserDirectory userDirectory,
            PermissionChanges permissionChanges,
            RedisPermissionRelay permissionRelay) {
        return new PageGrants(
                pagePermissions,
                permissionRepository,
                new JdbcGrantRepository(jdbcClient),
                workspaceMembership,
                userDirectory,
                everywhere(permissionChanges, permissionRelay));
    }
}
