package com.delta.admin.tenant;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantWxMaServiceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantWxMaServiceProviderTest {
    @Test
    void miniProgramClientIsScopedToTheConfiguredTenant() throws Exception {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        TenantProperties.Tenant a = tenant("shop-a.example.com");
        a.setMiniappEnabled(true);
        a.setMiniappAppId("wx-tenant-a");
        a.setMiniappSecret("secret-a-1234567890");
        TenantProperties.Tenant b = tenant("shop-b.example.com");
        properties.getTenants().put("tenant-a", a);
        properties.getTenants().put("tenant-b", b);
        TenantRegistry registry = new TenantRegistry(properties);
        TenantWxMaServiceProvider provider = new TenantWxMaServiceProvider();
        ReflectionTestUtils.setField(provider, "tenantProperties", properties);
        ReflectionTestUtils.setField(provider, "tenantRegistry", registry);

        var serviceA = TenantContext.callAs("tenant-a", provider::get);
        var serviceA2 = TenantContext.callAs("tenant-a", provider::get);
        var serviceB = TenantContext.callAs("tenant-b", provider::get);

        assertThat(serviceA).isSameAs(serviceA2);
        assertThat(serviceA.getWxMaConfig().getAppid()).isEqualTo("wx-tenant-a");
        assertThat(serviceB).isNull();
        assertThatThrownBy(provider::get).isInstanceOf(BusinessException.class);
    }

    private TenantProperties.Tenant tenant(String host) {
        TenantProperties.Tenant tenant = new TenantProperties.Tenant();
        tenant.setHosts(List.of(host));
        return tenant;
    }
}
