package com.delta.admin.controller;

import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.EmailNotificationService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
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
    public R<EmailNotificationService.Settings> settings() {
        platform.requireFeature(EmailNotificationService.FEATURE_ID);
        service.ensureDefaults();
        return R.ok(service.settings());
    }

    @PutMapping("/settings")
    @OpLog(module = "email", operation = "保存邮件通知配置")
    public R<EmailNotificationService.Settings> save(@RequestBody EmailNotificationService.SettingsRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID);
        return R.ok(service.save(request));
    }

    @PostMapping("/test")
    @OpLog(module = "email", operation = "邮件通知发送测试")
    public R<Integer> test(@RequestBody(required = false) EmailNotificationService.TestRequest request) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID);
        return R.ok(service.test(request), "测试邮件已进入发送队列");
    }

    @GetMapping("/records")
    public R<List<Map<String, Object>>> records(@RequestParam(defaultValue = "200") int limit) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID);
        return R.ok(service.records(limit));
    }

    @PostMapping("/records/{id}/retry")
    @OpLog(module = "email", operation = "重试邮件通知")
    public R<Void> retry(@PathVariable String id) {
        platform.requireFeature(EmailNotificationService.FEATURE_ID);
        service.retry(id);
        return R.ok();
    }
}
