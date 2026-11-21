package com.delta.reporting.service;

import com.delta.common.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encapsulates the statistics queries used by the admin statistics endpoints. */
@Service
@RequiredArgsConstructor
public class AdminStatisticsService {
    private final StatsMapper statsMapper;

    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<>();
        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();

        data.put("todayNewUsers", statsMapper.countUsersByDate(today));
        data.put("yesterdayNewUsers", statsMapper.countUsersByDate(yesterday));
        data.put("todayOrders", statsMapper.countOrdersByDate(today));
        data.put("todayAmount", statsMapper.sumOrderAmountByDate(today));
        data.put("totalUsers", statsMapper.countTotalUsers());
        data.put("totalPlayers", statsMapper.countTotalPlayers());
        data.put("totalOrders", statsMapper.countTotalOrders());
        data.put("totalAmount", statsMapper.sumTotalOrderAmount());
        return data;
    }

    public Map<String, Object> orderStats(String period) {
        Map<String, Object> data = new HashMap<>();
        data.put("statusDistribution", statsMapper.orderStatusDistributionCnt());
        data.put("trend", statsMapper.orderTrend7DaysDt());

        Long total = statsMapper.countOrdersExcludingPending();
        Long completed = statsMapper.countCompletedOrders();
        data.put("completionRate", total > 0 ? completed * 100.0 / total : 0);
        return data;
    }

    public Map<String, Object> userStats() {
        Map<String, Object> data = new HashMap<>();
        data.put("newUserTrend", statsMapper.newUserTrend30Days());

        Long paidUsers = statsMapper.countPaidUsers();
        Long totalUsers = statsMapper.countTotalUsers();
        data.put("paidUserRate", totalUsers > 0 ? paidUsers * 100.0 / totalUsers : 0);
        return data;
    }

    public List<Map<String, Object>> userSpendingRank(int limit) {
        return statsMapper.userSpendingRank(Math.min(limit, 200));
    }

    public Map<String, Object> playerStats() {
        Map<String, Object> data = new HashMap<>();
        data.put("totalPlayers", statsMapper.countTotalPlayers());
        data.put("activePlayers", statsMapper.countActivePlayers());
        data.put("ratingDistribution", statsMapper.playerRatingDistribution());
        data.put("incomeRank", statsMapper.playerIncomeRankTop10());
        return data;
    }

    public Map<String, Object> incomeDaily(LocalDate startDate, LocalDate endDate) {
        LocalDate resolvedEnd = endDate != null ? endDate : LocalDate.now();
        LocalDate resolvedStart = startDate != null ? startDate : resolvedEnd.minusDays(29);
        if (resolvedStart.isAfter(resolvedEnd)) {
            LocalDate temp = resolvedStart;
            resolvedStart = resolvedEnd;
            resolvedEnd = temp;
        }

        LocalDateTime start = resolvedStart.atStartOfDay();
        LocalDateTime end = resolvedEnd.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rawList = statsMapper.incomeDailyStatsByRange(start, end);
        Map<String, Map<String, Object>> rawMap = new HashMap<>();
        for (Map<String, Object> row : rawList) {
            rawMap.put(String.valueOf(row.get("statDate")), row);
        }
        List<Map<String, Object>> orderDetailList = statsMapper.incomeDailyOrderDetailsByRange(start, end);
        Map<String, List<Map<String, Object>>> orderDetailMap = new HashMap<>();
        for (Map<String, Object> row : orderDetailList) {
            String statDate = String.valueOf(row.get("statDate"));
            orderDetailMap.computeIfAbsent(statDate, key -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        long totalOrderCount = 0L;
        BigDecimal totalOrderAmount = BigDecimal.ZERO;
        BigDecimal totalPlayerIncome = BigDecimal.ZERO;
        BigDecimal totalCommissionIncome = BigDecimal.ZERO;

        for (LocalDate date = resolvedStart; !date.isAfter(resolvedEnd); date = date.plusDays(1)) {
            String key = date.toString();
            Map<String, Object> raw = rawMap.get(key);
            long orderCount = raw != null ? toLong(raw.get("orderCount")) : 0L;
            BigDecimal orderAmount = raw != null ? toBigDecimal(raw.get("orderAmount")) : BigDecimal.ZERO;
            BigDecimal playerIncome = raw != null ? toBigDecimal(raw.get("playerIncome")) : BigDecimal.ZERO;
            BigDecimal commissionIncome = raw != null ? toBigDecimal(raw.get("commissionIncome")) : BigDecimal.ZERO;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("statDate", key);
            item.put("orderCount", orderCount);
            item.put("orderAmount", orderAmount);
            item.put("playerIncome", playerIncome);
            item.put("commissionIncome", commissionIncome);
            item.put("orders", orderDetailMap.getOrDefault(key, new ArrayList<>()));
            list.add(item);

            totalOrderCount += orderCount;
            totalOrderAmount = totalOrderAmount.add(orderAmount);
            totalPlayerIncome = totalPlayerIncome.add(playerIncome);
            totalCommissionIncome = totalCommissionIncome.add(commissionIncome);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderCount", totalOrderCount);
        summary.put("orderAmount", totalOrderAmount);
        summary.put("playerIncome", totalPlayerIncome);
        summary.put("commissionIncome", totalCommissionIncome);

        Map<String, Object> data = new HashMap<>();
        data.put("startDate", resolvedStart);
        data.put("endDate", resolvedEnd);
        data.put("summary", summary);
        data.put("list", list);
        return data;
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }
}
