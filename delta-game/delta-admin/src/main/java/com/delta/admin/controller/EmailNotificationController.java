package com.delta.admin.controller;

import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.EmailNotificationService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/email-notifications")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
public class EmailNotificationController {
    private final PlatformService platform;
    private final EmailNotificationService service;

    public EmailNotificationController(PlatformService platform, EmailNotificationService service) {
        this.platform = platform;
        this.service = service;
    }

    @GetMapping({"", "/settings"})
    public R<EmailNotificationService.Settings> settings(HttpServletRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID, request.getServerName());
        service.ensureDefaults();
        return R.ok(service.settings());
    }

    @PutMapping("/settings")
    @OpLog(module = "email", operation = "保存邮件通知配置")
    public R<EmailNotificationService.Settings> save(@RequestBody EmailNotificationService.SettingsRequest body,
                                                     HttpServletRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID, request.getServerName());
        return R.ok(service.save(body));
    }

    @PostMapping("/test")
    @OpLog(module = "email", operation = "邮件通知发送测试")
    public R<Integer> test(@RequestBody(required = false) EmailNotificationService.TestRequest body,
                           HttpServletRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID, request.getServerName());
        return R.ok(service.test(body), "测试邮件已进入发送队列");
    }

    @GetMapping("/records")
    public R<List<Map<String, Object>>> records(@RequestParam(defaultValue = "200") int limit,
                                                HttpServletRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID, request.getServerName());
        return R.ok(service.records(limit));
    }

    @PostMapping("/records/{id}/retry")
    @OpLog(module = "email", operation = "重试邮件通知")
    public R<Void> retry(@PathVariable String id, HttpServletRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID, request.getServerName());
        service.retry(id);
        return R.ok();
    }
}
