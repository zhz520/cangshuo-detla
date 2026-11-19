package com.delta.admin.analytics;

import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.StatsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Aggregates the operational dashboard without exposing anonymous identifiers. */
@Service
public class DashboardAnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PLAYER_SCOPES = Set.of("ONLINE", "OFFLINE", "FROZEN");

    private final StatsMapper statsMapper;
    private final Clock clock;

    @Autowired
    public DashboardAnalyticsService(StatsMapper statsMapper) {
        this(statsMapper, Clock.system(BUSINESS_ZONE));
    }

    DashboardAnalyticsService(StatsMapper statsMapper, Clock clock) {
        this.statsMapper = statsMapper;
        this.clock = clock;
    }

    public Map<String, Object> snapshot() {
        LocalDate today = LocalDate.now(clock);
        LocalDate firstDay = today.minusDays(29);
        LocalDate endExclusive = today.plusDays(1);
        LocalDateTime start = firstDay.atStartOfDay();
        LocalDateTime end = endExclusive.atStartOfDay();

        Map<LocalDate, Daily> daily = emptyRange(firstDay, endExclusive);
        for (Map<String, Object> row : statsMapper.visitorAnalyticsByDate(firstDay, endExclusive)) {
            Daily item = dailyFor(daily, row);
            item.uniqueVisitors = toLong(value(row, "uniqueVisitors"));
            item.activeUsers = toLong(value(row, "activeUsers"));
        }
        for (Map<String, Object> row : statsMapper.newUsersAnalyticsByDate(start, end)) {
            dailyFor(daily, row).newUsers = toLong(value(row, "newUsers"));
        }
        for (Map<String, Object> row : statsMapper.orderAnalyticsByDate(start, end)) {
            Daily item = dailyFor(daily, row);
            item.orders = toLong(value(row, "orders"));
            item.effectiveOrders = toLong(value(row, "effectiveOrders"));
            item.revenue = toDecimal(value(row, "revenue"));
            item.completedOrders = toLong(value(row, "completedOrders"));
        }
        for (Map<String, Object> row : statsMapper.refundAnalyticsByDate(start, end)) {
            dailyFor(daily, row).refunds = toDecimal(value(row, "refunds"));
        }

        Daily todayMetrics = daily.get(today);
        Daily yesterdayMetrics = daily.get(today.minusDays(1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timezone", BUSINESS_ZONE.getId());
        result.put("today", todayMetrics.toMap());
        result.put("yesterday", yesterdayMetrics.toMap());
        result.put("comparison", comparisons(todayMetrics, yesterdayMetrics));
        result.put("trend7Days", trend(daily, today.minusDays(6), today));
        result.put("trend30Days", trend(daily, firstDay, today));
        result.put("funnel", funnel(todayMetrics));
        result.put("presence", presence());
        return result;
    }

    public Map<String, Object> players(String scope, int page, int pageSize, String keyword) {
        String normalizedScope = scope == null ? "ONLINE" : scope.trim().toUpperCase(Locale.ROOT);
        if (!PLAYER_SCOPES.contains(normalizedScope)) {
            throw new BusinessException(400, "玩家筛选范围无效");
        }
        int safePage = Math.max(1, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String safeKeyword = keyword == null ? "" : keyword.trim();
        if (safeKeyword.length() > 64) safeKeyword = safeKeyword.substring(0, 64);
        Long total = statsMapper.countPlayersForAnalytics(normalizedScope, safeKeyword);
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.playersForAnalytics(
                normalizedScope, safeKeyword, (safePage - 1) * safeSize, safeSize)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", value(row, "id"));
            item.put("nickname", value(row, "nickname"));
            item.put("avatar", value(row, "avatar"));
            item.put("level", value(row, "level"));
            item.put("levelName", value(row, "levelName"));
            item.put("onlineStatus", value(row, "onlineStatus"));
            item.put("activeOrderCount", toLong(value(row, "activeOrderCount")));
            item.put("lastOnlineAt", value(row, "lastOnlineAt"));
            sanitized.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", normalizedScope);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("total", total == null ? 0L : total);
        result.put("rows", sanitized);
        return result;
    }

    private Map<LocalDate, Daily> emptyRange(LocalDate start, LocalDate endExclusive) {
        Map<LocalDate, Daily> result = new TreeMap<>();
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            result.put(date, new Daily(date));
        }
        return result;
    }

    private Daily dailyFor(Map<LocalDate, Daily> daily, Map<String, Object> row) {
        Object raw = value(row, "statDate");
        if (raw == null) throw new IllegalStateException("统计数据缺少日期");
        LocalDate date = raw instanceof LocalDate localDate ? localDate : LocalDate.parse(String.valueOf(raw));
        return daily.computeIfAbsent(date, Daily::new);
    }

    private List<Map<String, Object>> trend(Map<LocalDate, Daily> daily, LocalDate start, LocalDate end) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            result.add(daily.get(date).toMap());
        }
        return result;
    }

    private Map<String, Object> comparisons(Daily current, Daily previous) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uniqueVisitors", compare(current.uniqueVisitors, previous.uniqueVisitors));
        result.put("activeUsers", compare(current.activeUsers, previous.activeUsers));
        result.put("newUsers", compare(current.newUsers, previous.newUsers));
        result.put("orders", compare(current.orders, previous.orders));
        result.put("effectiveOrders", compare(current.effectiveOrders, previous.effectiveOrders));
        result.put("revenue", compare(current.revenue, previous.revenue));
        result.put("refunds", compare(current.refunds, previous.refunds));
        result.put("completedOrders", compare(current.completedOrders, previous.completedOrders));
        return result;
    }

    private Map<String, Object> compare(Number current, Number previous) {
        BigDecimal currentValue = toDecimal(current);
        BigDecimal previousValue = toDecimal(previous);
        BigDecimal delta = currentValue.subtract(previousValue);
        BigDecimal percent;
        if (previousValue.compareTo(BigDecimal.ZERO) == 0) {
            percent = currentValue.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(100);
        } else {
            percent = delta.multiply(BigDecimal.valueOf(100))
                    .divide(previousValue, 1, RoundingMode.HALF_UP);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", currentValue);
        result.put("previous", previousValue);
        result.put("delta", delta);
        result.put("percent", percent);
        result.put("direction", delta.signum() > 0 ? "UP" : delta.signum() < 0 ? "DOWN" : "FLAT");
        return result;
    }

    private List<Map<String, Object>> funnel(Daily metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        long visits = metrics.uniqueVisitors;
        result.add(stage("VISIT", "访问用户", visits, visits));
        result.add(stage("ORDER", "提交订单", metrics.orders, visits));
        result.add(stage("EFFECTIVE", "有效订单", metrics.effectiveOrders, visits));
        result.add(stage("COMPLETED", "完成订单", metrics.completedOrders, visits));
        return result;
    }

    private Map<String, Object> stage(String code, String label, long value, long base) {
        BigDecimal percent = base == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(base), 1, RoundingMode.HALF_UP);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", code);
        stage.put("label", label);
        stage.put("value", value);
        stage.put("rateFromVisits", percent);
        return stage;
    }

    private Map<String, Object> presence() {
        Map<String, Object> raw = statsMapper.playerPresenceCounts();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlinePlayers", toLong(raw == null ? null : value(raw, "onlinePlayers")));
        result.put("offlinePlayers", toLong(raw == null ? null : value(raw, "offlinePlayers")));
        result.put("frozenPlayers", toLong(raw == null ? null : value(raw, "frozenPlayers")));
        Long onDutyCs = statsMapper.countOnDutyCs();
        result.put("onDutyCs", onDutyCs == null ? 0L : onDutyCs);
        return result;
    }

    private Object value(Map<String, Object> row, String key) {
        if (row == null) return null;
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    private static BigDecimal toDecimal(Number value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return BigDecimal.valueOf(value.doubleValue());
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private static final class Daily {
        private final LocalDate date;
        private long uniqueVisitors;
        private long activeUsers;
        private long newUsers;
        private long orders;
        private long effectiveOrders;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal refunds = BigDecimal.ZERO;
        private long completedOrders;

        private Daily(LocalDate date) {
            this.date = date;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("uniqueVisitors", uniqueVisitors);
            row.put("activeUsers", activeUsers);
            row.put("newUsers", newUsers);
            row.put("orders", orders);
            row.put("effectiveOrders", effectiveOrders);
            row.put("revenue", revenue);
            row.put("refunds", refunds);
            row.put("completedOrders", completedOrders);
            return row;
        }
    }
}
