package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatAccountService;
import com.delta.notification.service.WechatOpenPlatformClient;
import com.delta.notification.service.WechatTemplateSetupJobs;
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
    private final WechatOpenPlatformClient openPlatform;
    private final WechatTemplateSetupJobs setup;

    @GetMapping("/admin/wechat/accounts/open-platform/status")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<WechatOpenPlatformClient.Availability> openPlatformStatus(HttpServletRequest request) {
        requireFeature(request);
        return R.ok(openPlatform.status());
    }

    @PostMapping("/admin/wechat/accounts/open-platform/start")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "发起公众号扫码授权")
    public R<WechatOpenPlatformClient.Authorization> startOpenPlatform(HttpServletRequest request) {
        requireFeature(request);
        return R.ok(setup.start(request.getServerName()));
    }

    @PostMapping("/admin/wechat/accounts/open-platform/complete")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<WechatOpenPlatformClient.Progress> completeOpenPlatform(@RequestBody OpenPlatformCompletion body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(setup.progress(body.state()));
    }

    public record OpenPlatformCompletion(String state) {}

    public record TemplateRepair(String action,String role,String scenario,String revision) {}

    @PostMapping("/admin/wechat/accounts/{id}/templates/provision")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "重试公众号模板自动配置")
    public R<Map<String,Object>> provision(@PathVariable String id,@RequestBody(required=false) TemplateRepair repair,HttpServletRequest request) {
        requireFeature(request);
        if(repair!=null) {
            if(!"REBUILD_MISSING".equals(repair.action())) throw new com.delta.common.exception.BusinessException(400,"不支持的模板操作");
            return R.ok(setup.repair(id,request.getServerName(),repair.role(),repair.scenario(),repair.revision()));
        }
        return R.ok(setup.retry(id,request.getServerName()));
    }

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
        var result=accounts.readiness(id);
        result.put("templateSetup",setup.report(id));
        return R.ok(result);
    }

    private void requireFeature(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
    }
}
