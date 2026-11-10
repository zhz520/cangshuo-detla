package com.delta.admin.tenant;

import com.delta.common.config.CorsConfig;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsTenantIsolationTest {
    @Test
    void sharedCorsAllowsOnlyAnOriginOwnedByTheRequestedTenant() {
        TenantProperties properties = properties();
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "tenantProperties", properties);
        ReflectionTestUtils.setField(config, "tenantRegistry", new TenantRegistry(properties));
        var source = config.corsConfigurationSource();

        assertThat(source.getCorsConfiguration(request("shop-a.example.com", "https://shop-a.example.com")))
                .isNotNull();
        assertThat(source.getCorsConfiguration(request("shop-a.example.com", "https://shop-b.example.com")))
                .isNull();
        assertThat(source.getCorsConfiguration(request("unknown.example.com", "https://shop-a.example.com")))
                .isNull();
    }

    private MockHttpServletRequest request(String host, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        request.addHeader("Origin", origin);
        return request;
    }

    private TenantProperties properties() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        TenantProperties.Tenant a = new TenantProperties.Tenant();
        a.setHosts(List.of("shop-a.example.com"));
        TenantProperties.Tenant b = new TenantProperties.Tenant();
        b.setHosts(List.of("shop-b.example.com"));
        properties.getTenants().put("tenant-a", a);
        properties.getTenants().put("tenant-b", b);
        return properties;
    }
}
