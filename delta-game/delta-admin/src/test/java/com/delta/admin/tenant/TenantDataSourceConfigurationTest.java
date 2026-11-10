package com.delta.admin.tenant;

import com.delta.common.tenant.TenantDataSourceConfiguration;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantDataSourceConfigurationTest {
    @Test
    void sharedModeSuppliesTheSinglePrimaryDataSourceInsteadOfTheLegacyDefault() {
        TenantProperties properties = properties();
        TenantRegistry registry = new TenantRegistry(properties);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(TenantDataSourceConfiguration.class)
                .withBean(TenantRegistry.class, () -> registry)
                .withPropertyValues(
                        "tenant.enabled=true",
                        "spring.datasource.url=jdbc:h2:mem:legacy_default;DB_CLOSE_DELAY=-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(TenantRoutingDataSource.class);
                    assertThat(((TenantRoutingDataSource) context.getBean(DataSource.class)).tenantCount()).isEqualTo(2);
                });
    }

    private TenantProperties properties() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        properties.getTenants().put("tenant-a", tenant("shop-a.example.com"));
        properties.getTenants().put("tenant-b", tenant("shop-b.example.com"));
        return properties;
    }

    private TenantProperties.Tenant tenant(String host) {
        TenantProperties.Tenant tenant = new TenantProperties.Tenant();
        tenant.setHosts(List.of(host));
        tenant.setJdbcUrl("jdbc:h2:mem:tenant_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        tenant.setUsername("sa");
        tenant.setDriverClassName("org.h2.Driver");
        tenant.setMaximumPoolSize(2);
        tenant.setMinimumIdle(0);
        return tenant;
    }
}
