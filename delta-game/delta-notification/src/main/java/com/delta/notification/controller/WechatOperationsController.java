package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatAccountService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WechatOperationsController {
    private final WechatAccountService accounts;
    private final PlatformService platform;

    @GetMapping("/admin/wechat/accounts")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> accounts(HttpServletRequest request) {
        requireFeature(request);
        return R.ok(accounts.accounts());
    }

    @PostMapping("/admin/wechat/accounts")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "保存公众号账号")
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(accounts.saveAccount(body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号账号")
    public R<Void> delete(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        accounts.deleteAccount(id);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/templates")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> templates(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(accounts.catalog(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/templates/sync")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "同步公众号模板")
    public R<List<Map<String, Object>>> sync(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(accounts.syncTemplates(id));
    }

    @PutMapping("/admin/wechat/accounts/{id}/templates")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "保存公众号模板映射")
    public R<Map<String, Object>> saveTemplate(@PathVariable String id, @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        requireFeature(request);
        return R.ok(accounts.saveTemplate(id, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/templates/{role}/{scenario}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号模板映射")
    public R<Void> deleteTemplate(@PathVariable String id, @PathVariable String role, @PathVariable String scenario,
                                  HttpServletRequest request) {
        requireFeature(request);
        accounts.deleteTemplate(id, role, scenario);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/readiness")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> readiness(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(accounts.readiness(id));
    }

    private void requireFeature(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
    }
}
