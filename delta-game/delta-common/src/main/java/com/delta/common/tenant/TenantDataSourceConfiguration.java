package com.delta.common.tenant;

import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates one bounded connection pool per active tenant only when shared mode is enabled. */
@Configuration
@ConditionalOnProperty(prefix = "tenant", name = "enabled", havingValue = "true")
public class TenantDataSourceConfiguration {
    @Bean
    @Primary
    public TenantRoutingDataSource tenantRoutingDataSource(TenantRegistry registry) throws SQLException {
        Map<String, DataSource> sources = new LinkedHashMap<>();
        try {
            for (TenantDefinition tenant : registry.activeTenants()) {
                if (!tenant.hasDataSource()) {
                    throw new IllegalStateException("启用中的租户缺少数据库连接配置: " + tenant.id());
                }
                sources.put(tenant.id(), createPool(tenant));
            }
            return new TenantRoutingDataSource(sources);
        } catch (RuntimeException | SQLException error) {
            for (DataSource source : sources.values()) {
                if (source instanceof DruidDataSource druid) druid.close();
            }
            throw error;
        }
    }

    private DruidDataSource createPool(TenantDefinition tenant) throws SQLException {
        DruidDataSource pool = new DruidDataSource();
        pool.setName("tenant-" + tenant.id());
        pool.setUrl(tenant.jdbcUrl());
        pool.setUsername(tenant.username());
        pool.setPassword(tenant.password());
        pool.setDriverClassName(tenant.driverClassName());
        pool.setInitialSize(0);
        pool.setMinIdle(tenant.minimumIdle());
        pool.setMaxActive(tenant.maximumPoolSize());
        pool.setMaxWait(tenant.connectionTimeoutMs());
        pool.setValidationQuery("SELECT 1");
        pool.setTestWhileIdle(true);
        pool.setTestOnBorrow(false);
        pool.setTestOnReturn(false);
        pool.setTimeBetweenEvictionRunsMillis(60_000L);
        pool.setMinEvictableIdleTimeMillis(300_000L);
        pool.setPoolPreparedStatements(false);
        pool.setUseUnfairLock(true);
        pool.init();
        return pool;
    }
}
