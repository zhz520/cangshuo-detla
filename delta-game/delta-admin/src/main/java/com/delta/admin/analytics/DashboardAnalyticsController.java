package com.delta.admin.analytics;

import com.delta.common.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shared analytics API. ADMIN has full data; CS receives operational metrics with order.read. */
@RestController
@RequestMapping({"/admin/analytics", "/cs/analytics"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','CS')")
public class DashboardAnalyticsController {
    private final DashboardAnalyticsService analyticsService;

    @GetMapping
    public R<Map<String, Object>> dashboard() {
        return R.ok(analyticsService.snapshot());
    }

    @GetMapping("/players")
    public R<Map<String, Object>> players(
            @RequestParam(defaultValue = "ONLINE") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String keyword) {
        return R.ok(analyticsService.players(scope, page, pageSize, keyword));
    }
}
