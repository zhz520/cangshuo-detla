package com.delta.reporting.analytics;

import com.delta.common.mapper.StatsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardAnalyticsServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-08T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void aggregationFillsMissingDaysAndBuildsComparisonsTrendsAndFunnel() {
        StatsMapper mapper = mock(StatsMapper.class);
        when(mapper.visitorAnalyticsByDate(any(), any())).thenReturn(List.of(
                row("statDate", LocalDate.parse("2026-11-07"), "uniqueVisitors", 90, "activeUsers", 51),
                row("statDate", LocalDate.parse("2026-11-08"), "uniqueVisitors", 120, "activeUsers", 72)
        ));
        when(mapper.newUsersAnalyticsByDate(any(), any())).thenReturn(List.of(
                row("statDate", LocalDate.parse("2026-11-08"), "newUsers", 8)
        ));
        when(mapper.orderAnalyticsByDate(any(), any())).thenReturn(List.of(
                row("statDate", LocalDate.parse("2026-11-07"), "orders", 20, "effectiveOrders", 15,
                        "revenue", new BigDecimal("100.00"), "completedOrders", 10),
                row("statDate", LocalDate.parse("2026-11-08"), "orders", 30, "effectiveOrders", 24,
                        "revenue", new BigDecimal("300.00"), "completedOrders", 18)
        ));
        when(mapper.refundAnalyticsByDate(any(), any())).thenReturn(List.of(
                row("statDate", LocalDate.parse("2026-11-08"), "refunds", new BigDecimal("12.50"))
        ));
        when(mapper.playerPresenceCounts()).thenReturn(Map.of(
                "onlinePlayers", 9, "offlinePlayers", 6, "frozenPlayers", 2));
        when(mapper.countOnDutyCs()).thenReturn(3L);

        Map<String, Object> snapshot = new DashboardAnalyticsService(mapper, CLOCK).snapshot();

        Map<?, ?> today = (Map<?, ?>) snapshot.get("today");
        Map<?, ?> comparison = (Map<?, ?>) snapshot.get("comparison");
        Map<?, ?> orderComparison = (Map<?, ?>) comparison.get("orders");
        List<?> trend7 = (List<?>) snapshot.get("trend7Days");
        List<?> funnel = (List<?>) snapshot.get("funnel");
        Map<?, ?> presence = (Map<?, ?>) snapshot.get("presence");

        assertThat(today.get("date")).isEqualTo("2026-11-08");
        assertThat(today.get("uniqueVisitors")).isEqualTo(120L);
        assertThat(today.get("activeUsers")).isEqualTo(72L);
        assertThat(today.get("refunds")).isEqualTo(new BigDecimal("12.50"));
        assertThat(orderComparison.get("direction")).isEqualTo("UP");
        assertThat((BigDecimal) orderComparison.get("percent")).isEqualByComparingTo("50.0");
        assertThat(trend7).hasSize(7);
        assertThat(((Map<?, ?>) trend7.get(0)).get("orders")).isEqualTo(0L);
        assertThat(((Map<?, ?>) funnel.get(0)).get("value")).isEqualTo(120L);
        assertThat(((Map<?, ?>) funnel.get(1)).get("value")).isEqualTo(30L);
        assertThat(((Map<?, ?>) funnel.get(2)).get("value")).isEqualTo(24L);
        assertThat(presence.get("onDutyCs")).isEqualTo(3L);
        assertThat(presence.get("onlinePlayers")).isEqualTo(9L);
    }

    @Test
    void playerDrilldownWhitelistsAnalyticsFieldsAndNeverReturnsPrivateIdentityData() {
        StatsMapper mapper = mock(StatsMapper.class);
        when(mapper.countPlayersForAnalytics("ONLINE", "北泽")).thenReturn(1L);
        when(mapper.playersForAnalytics("ONLINE", "北泽", 0, 20)).thenReturn(List.of(row(
                "id", 8L, "nickname", "北泽", "avatar", "/file/a.jpg", "level", 3,
                "levelName", "魔王", "onlineStatus", "ONLINE", "activeOrderCount", 2,
                "lastOnlineAt", "2026-11-08 11:00:00", "phone", "13800000000",
                "realName", "不应返回", "visitorKey", "raw-sensitive-value")));

        Map<String, Object> page = new DashboardAnalyticsService(mapper, CLOCK)
                .players(" online ", 1, 20, " 北泽 ");
        @SuppressWarnings("unchecked")
        Map<String, Object> player = ((List<Map<String, Object>>) page.get("rows")).get(0);

        assertThat(player).containsOnlyKeys(
                "id", "nickname", "avatar", "level", "levelName", "onlineStatus", "activeOrderCount", "lastOnlineAt");
        assertThat(player.values()).doesNotContain("13800000000", "不应返回", "raw-sensitive-value");
    }

    @Test
    void analyticsEndpointsAllowAdminAndCustomerServiceWithRoutePolicy() {
        RequestMapping routes = DashboardAnalyticsController.class.getAnnotation(RequestMapping.class);
        PreAuthorize authorization = DashboardAnalyticsController.class.getAnnotation(PreAuthorize.class);

        assertThat(Arrays.asList(routes.value())).containsExactlyInAnyOrder("/admin/analytics", "/cs/analytics");
        assertThat(authorization.value()).isEqualTo("hasAnyRole('ADMIN','CS')");
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
