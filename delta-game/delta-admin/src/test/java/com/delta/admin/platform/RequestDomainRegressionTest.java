package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RequestDomainRegressionTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void forwardedHostCannotBypassTheProjectDomainRestriction() throws Exception {
        var remote = mock(PlatformRemote.class);
        when(remote.project()).thenReturn(json.createObjectNode());
        when(remote.domainAllowed("shop.example.com")).thenReturn(true);
        var request = new MockHttpServletRequest("POST", "/order");
        request.setServletPath("/order");
        request.setServerName("unlicensed.example");
        request.addHeader("X-Forwarded-Host", "shop.example.com");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new ProjectMaintenanceFilter(remote, json).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        verify(remote).domainAllowed("unlicensed.example");
    }

    @Test void aRawForwardedHostDoesNotOverrideAnAllowedServerName() throws Exception {
        var remote = mock(PlatformRemote.class);
        when(remote.project()).thenReturn(json.createObjectNode());
        when(remote.domainAllowed("shop.example.com")).thenReturn(true);
        var request = new MockHttpServletRequest("POST", "/order");
        request.setServletPath("/order");
        request.setServerName("shop.example.com");
        request.addHeader("X-Forwarded-Host", "attacker.example");
        var chain = new MockFilterChain();

        new ProjectMaintenanceFilter(remote, json).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test void paidIncomeReportChecksTheServerNameResolvedByTheContainer() {
        var analysis = mock(IncomeAnalysisService.class);
        var controller = new PlatformController(mock(PlatformService.class), mock(PlatformRemote.class), analysis);
        var request = new MockHttpServletRequest();
        request.setServerName("unlicensed.example");
        request.addHeader("X-Forwarded-Host", "shop.example.com");
        LocalDate day = LocalDate.of(2026, 11, 1);

        controller.income(day, day, request);

        verify(analysis).compare(day, day, "unlicensed.example");
    }
}
