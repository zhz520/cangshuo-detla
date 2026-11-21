package com.delta.reporting.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AnalyticsVisitInterceptorTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyH5BusinessRequestsAreSampledAndAuthenticatedUserIsPassed() {
        AnalyticsVisitRecorder recorder = mock(AnalyticsVisitRecorder.class);
        AnalyticsVisitInterceptor interceptor = new AnalyticsVisitInterceptor(recorder);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/order/my");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        verify(recorder).record(request, "USER", 7L);
    }

    @Test
    void staffPagesPreflightAndPaymentCallbacksNeverInflateVisitorMetrics() {
        AnalyticsVisitRecorder recorder = mock(AnalyticsVisitRecorder.class);
        AnalyticsVisitInterceptor interceptor = new AnalyticsVisitInterceptor(recorder);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(new MockHttpServletRequest("GET", "/api/cs/dashboard"), response, new Object());
        interceptor.preHandle(new MockHttpServletRequest("OPTIONS", "/api/order/my"), response, new Object());
        interceptor.preHandle(new MockHttpServletRequest("POST", "/api/pay/wx/notify"), response, new Object());

        verify(recorder, never()).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
