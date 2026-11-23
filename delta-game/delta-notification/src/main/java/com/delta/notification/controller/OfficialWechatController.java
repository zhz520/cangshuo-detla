package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.notification.service.OfficialWechatService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OfficialWechatController {
    private final OfficialWechatService service;
    private final PlatformService platform;

    @GetMapping("/common/wechat/binding")
    public R<Map<String, Object>> binding() {
        return R.ok(service.binding(SecurityUtils.getUserType(), SecurityUtils.getUserId()));
    }

    @PostMapping("/common/wechat/binding/start")
    public R<String> start(HttpServletRequest request) {
        return R.ok(service.startBinding(SecurityUtils.getUserType(), SecurityUtils.getUserId(), request.getServerName()));
    }

    @PostMapping("/common/wechat/binding/complete")
    public R<Void> complete(@RequestBody Map<String, String> body, HttpServletRequest request) throws Exception {
        service.finishBinding(SecurityUtils.getUserType(), SecurityUtils.getUserId(), body.get("code"), body.get("state"), request.getServerName());
        return R.ok();
    }

    @DeleteMapping("/common/wechat/binding")
    @OpLog(module = "wechat", operation = "解除公众号通知绑定")
    public R<Void> remove() {
        service.removeNotificationBinding(SecurityUtils.getUserType(), SecurityUtils.getUserId());
        return R.ok();
    }

    @GetMapping("/admin/wechat/notifications")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> notices(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
        return R.ok(service.notificationQueue());
    }

    @PostMapping("/admin/wechat/notifications/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "重试公众号通知")
    public R<Void> retry(@PathVariable String id, HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
        service.retryNotification(id);
        return R.ok();
    }

    @GetMapping("/admin/sms/notifications")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> smsNotices() {
        return R.ok(service.smsQueue());
    }

    @PostMapping("/admin/sms/notifications/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "sms", operation = "重试业务短信通知")
    public R<Void> retrySms(@PathVariable String id) {
        service.retrySms(id);
        return R.ok();
    }
}
