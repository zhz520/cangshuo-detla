package com.delta.admin.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantStartup;

@Component
@Order(-90)
@RequiredArgsConstructor
public class AnalyticsSchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Autowired(required = false)
    private TenantRegistry tenantRegistry;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        TenantStartup.forEachActive(tenantRegistry, this::initializeCurrentTenant);
    }

    private void initializeCurrentTenant() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/analytics-v1.sql"));
        }
    }
}
