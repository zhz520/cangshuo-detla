package com.delta.cs.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.cs.service.CsDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 客服工作台 Dashboard */
@RestController
@RequestMapping("/cs/dashboard")
@RequiredArgsConstructor
public class CsDashboardController {
    private final CsDashboardService dashboard;
    private final com.delta.common.security.service.StaffPermissions staffPermissions;

    @GetMapping
    public R<Map<String, Object>> dashboard() {
        return R.ok(dashboard.dashboard(SecurityUtils.getUserId(), staffPermissions.allowed("finance.read")));
    }
}
