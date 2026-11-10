package com.delta.common.maintenance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceFilterTest {
    private final MaintenanceState state = new MaintenanceState();
    private final MaintenanceFilter filter = new MaintenanceFilter(state);

    @AfterEach
    void reset() {
        state.setEnabled(true);
    }

    @Test
    void paymentCallbacksAndStatusEndpointsStayReachableDuringMaintenance() throws Exception {
        state.setEnabled(false);
        for (String path : List.of("/pay/wx/notify", "/pay/wx/refund-notify", "/pay/wx/official/callback",
                "/system/config/site", "/system/maintenance/status", "/actuator/health", "/maintenance")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void businessRequestsAndPreflightAreHandledAsExpected() throws Exception {
        state.setEnabled(false);
        MockHttpServletRequest blocked = new MockHttpServletRequest("POST", "/order");
        blocked.setServletPath("/order");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(blocked, blockedResponse, blockedChain);
        assertThat(blockedResponse.getStatus()).isEqualTo(503);
        assertThat(blockedChain.getRequest()).isNull();

        MockHttpServletRequest options = new MockHttpServletRequest("OPTIONS", "/order");
        options.setServletPath("/order");
        MockHttpServletResponse optionsResponse = new MockHttpServletResponse();
        MockFilterChain optionsChain = new MockFilterChain();
        filter.doFilter(options, optionsResponse, optionsChain);
        assertThat(optionsResponse.getStatus()).isEqualTo(200);
        assertThat(optionsChain.getRequest()).isNotNull();
    }
}
