package com.delta.admin.tenant;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRequestFilterTest {
    @Test
    void setsAndClearsTheResolvedTenantForTheWholeRequest() throws Exception {
        TenantProperties properties = properties();
        TenantRequestFilter filter = new TenantRequestFilter(new TenantRegistry(properties), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/config/site");
        request.setServerName("shop-a.example.com");
        AtomicReference<String> observed = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> observed.set(TenantContext.currentIdOrNull()));

        assertThat(observed).hasValue("tenant-a");
        assertThat(TenantContext.currentIdOrNull()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsAnUnregisteredHostEvenWhenForwardedHostIsForged() throws Exception {
        TenantRequestFilter filter = new TenantRequestFilter(new TenantRegistry(properties()), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/list");
        request.setServerName("attacker.example.com");
        request.addHeader("X-Forwarded-Host", "shop-a.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void disabledSharedModeKeepsLegacySingleTenantRequestsUnchanged() throws Exception {
        TenantProperties properties = properties();
        properties.setEnabled(false);
        TenantRequestFilter filter = new TenantRequestFilter(new TenantRegistry(properties), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/list");
        request.setServerName("not-in-the-registry.example.com");
        AtomicReference<String> observed = new AtomicReference<>("unset");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> observed.set(TenantContext.currentIdOrNull()));

        assertThat(observed.get()).isNull();
    }

    @Test
    void inactiveTenantCannotReachBusinessHandlers() throws Exception {
        TenantProperties properties = properties();
        properties.getTenants().get("tenant-b").setActive(false);
        TenantRequestFilter filter = new TenantRequestFilter(new TenantRegistry(properties), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/list");
        request.setServerName("shop-b.example.com");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
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
