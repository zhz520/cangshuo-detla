package com.delta.admin.analytics;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AnalyticsVisitRecorderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-08T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void visitorWritesAreThrottledAndRawIpAndUserAgentNeverReachTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AnalyticsVisitRecorder recorder = new AnalyticsVisitRecorder(jdbc, CLOCK,
                "test-secret-that-is-long-enough-for-an-hmac-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order/my");
        request.setRemoteAddr("203.0.113.55");
        request.addHeader("User-Agent", "Private Browser/1.0");

        recorder.record(request, "USER", 42L);
        recorder.record(request, "USER", 42L);

        ArgumentCaptor<Object> date = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> visitor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> device = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> userType = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> userId = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, times(1)).update(anyString(), date.capture(), visitor.capture(), device.capture(),
                userType.capture(), userId.capture());

        assertThat(String.valueOf(visitor.getValue())).hasSize(64);
        assertThat(String.valueOf(device.getValue())).hasSize(64);
        assertThat(Arrays.asList(visitor.getValue(), device.getValue()))
                .allSatisfy(value -> assertThat(String.valueOf(value))
                        .doesNotContain("203.0.113.55", "Private Browser"));
        assertThat(String.valueOf(userType.getValue())).isEqualTo("USER");
        assertThat(userId.getValue()).isEqualTo(42L);
        assertThat(String.valueOf(date.getValue())).isEqualTo("2026-11-08");
    }
}
