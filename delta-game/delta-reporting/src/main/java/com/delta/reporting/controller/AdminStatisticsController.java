package com.delta.reporting.controller;

import com.delta.common.domain.R;
import com.delta.reporting.service.AdminStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 管理后台数据统计 */
@RestController
@RequestMapping("/admin/statistics")
@RequiredArgsConstructor
public class AdminStatisticsController {
    private final AdminStatisticsService statisticsService;

    /** 数据概览 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(statisticsService.overview());
    }

    /** 订单统计 */
    @GetMapping("/order")
    public R<Map<String, Object>> orderStats(@RequestParam(value = "period", defaultValue = "day") String period) {
        return R.ok(statisticsService.orderStats(period));
    }

    /** 用户统计 */
    @GetMapping("/user")
    public R<Map<String, Object>> userStats() {
        return R.ok(statisticsService.userStats());
    }

    /** 用户消费榜单 */
    @GetMapping("/user-spending-rank")
    public R<List<Map<String, Object>>> userSpendingRank(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return R.ok(statisticsService.userSpendingRank(limit));
    }

    /** 打手统计 */
    @GetMapping("/player")
    public R<Map<String, Object>> playerStats() {
        return R.ok(statisticsService.playerStats());
    }

    /** 收益日报 */
    @GetMapping("/income-daily")
    public R<Map<String, Object>> incomeDaily(
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(statisticsService.incomeDaily(startDate, endDate));
    }
}
