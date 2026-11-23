package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatOperationsService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WechatOperationsToolsController {
    private final WechatOperationsService service;
    private final PlatformService platform;

    @GetMapping("/admin/wechat/accounts/{id}/menu")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> menu(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.menu(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "保存公众号菜单")
    public R<Map<String, Object>> saveMenu(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveMenu(id, body));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu/publish")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "发布公众号菜单")
    public R<Map<String, Object>> publishMenu(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.publishMenu(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu/import")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "读取公众号菜单")
    public R<Map<String, Object>> importMenu(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.importMenu(id));
    }

    @GetMapping("/admin/wechat/accounts/{id}/autoreplies")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> autoReplies(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.autoReplies(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/autoreplies")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "新增公众号自动回复")
    public R<Map<String, Object>> createAutoReply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveAutoReply(id, null, body));
    }

    @PutMapping("/admin/wechat/accounts/{id}/autoreplies/{ruleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "编辑公众号自动回复")
    public R<Map<String, Object>> updateAutoReply(@PathVariable String id, @PathVariable long ruleId,
                                                   @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveAutoReply(id, ruleId, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/autoreplies/{ruleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号自动回复")
    public R<Void> deleteAutoReply(@PathVariable String id, @PathVariable long ruleId, HttpServletRequest request) {
        requireFeature(request);
        service.deleteAutoReply(id, ruleId);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/qrcodes")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> qrCodes(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.qrCodes(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/qrcodes")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "创建公众号渠道二维码")
    public R<Map<String, Object>> createQr(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.createQr(id, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/qrcodes/{qrId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号渠道二维码")
    public R<Void> deleteQr(@PathVariable String id, @PathVariable String qrId, HttpServletRequest request) {
        requireFeature(request);
        service.deleteQr(id, qrId);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/fans")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> fans(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.fans(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/fans/sync")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "同步公众号粉丝")
    public R<Map<String, Object>> syncFans(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.syncFans(id));
    }

    private void requireFeature(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
    }
}
