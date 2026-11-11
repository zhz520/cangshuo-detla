package com.delta.admin.tenant;

import com.delta.common.security.service.RateLimitService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.websocket.ChatHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketTenantIsolationTest {
    @Test
    void handshakeBindsHostAndRequiresAnOriginOwnedByTheSameTenant() {
        Fixture fixture = fixture();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = fixture.interceptor.beforeHandshake(
                request("shop-a.example.com", "https://shop-a.example.com"),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("tenantId", "tenant-a");
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }

    @Test
    void handshakeRejectsUnknownHostsMissingOriginsAndCrossTenantOrigins() {
        Fixture fixture = fixture();

        assertThat(fixture.accepts("unknown.example.com", "https://shop-a.example.com")).isFalse();
        assertThat(fixture.accepts("shop-a.example.com", null)).isFalse();
        assertThat(fixture.accepts("shop-a.example.com", "https://shop-b.example.com")).isFalse();
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }

    @Test
    void rejectedCrossTenantOriginUsesForbiddenStatus() {
        Fixture fixture = fixture();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = fixture.interceptor.beforeHandshake(
                request("shop-a.example.com", "https://shop-b.example.com"),
                new ServletServerHttpResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private ServletServerHttpRequest request(String host, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/chat");
        request.setScheme("https");
        request.setServerName(host);
        request.setServerPort(443);
        if (origin != null) request.addHeader(HttpHeaders.ORIGIN, origin);
        return new ServletServerHttpRequest(request);
    }

    private Fixture fixture() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        TenantProperties.Tenant a = new TenantProperties.Tenant();
        a.setHosts(List.of("shop-a.example.com"));
        TenantProperties.Tenant b = new TenantProperties.Tenant();
        b.setHosts(List.of("shop-b.example.com"));
        properties.getTenants().put("tenant-a", a);
        properties.getTenants().put("tenant-b", b);
        TenantRegistry registry = new TenantRegistry(properties);
        RateLimitService limits = mock(RateLimitService.class);
        return new Fixture(new ChatHandshakeInterceptor(limits, registry, properties));
    }

    private record Fixture(ChatHandshakeInterceptor interceptor) {
        boolean accepts(String host, String origin) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/chat");
            request.setScheme("https");
            request.setServerName(host);
            request.setServerPort(443);
            if (origin != null) request.addHeader(HttpHeaders.ORIGIN, origin);
            return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(new MockHttpServletResponse()),
                    mock(WebSocketHandler.class), new HashMap<>());
        }
    }
}
