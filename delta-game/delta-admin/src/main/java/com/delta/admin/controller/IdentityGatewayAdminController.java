package com.delta.admin.controller;

import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.AuthorityServiceSettings;
import com.delta.admin.service.IdentityBillingClient;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/identity-service")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
public class IdentityGatewayAdminController {
    private final PlatformService platform;
    private final IdentityBillingClient identity;
    private final AuthorityServiceSettings settings;

    public record SettingsRequest(String provider, Boolean enabled) {}

    private void requireFeature(HttpServletRequest request) {
        platform.requireFeature("identity_verification", request.getServerName());
    }

    @GetMapping("/settings")
    public R<AuthorityServiceSettings.IdentitySettings> settings(HttpServletRequest request) {
        requireFeature(request);
        return R.ok(settings.identity());
    }

    @PutMapping("/settings")
    @OpLog(module="system", operation="修改授权中心实名服务设置")
    public R<AuthorityServiceSettings.IdentitySettings> update(@RequestBody SettingsRequest body, HttpServletRequest request) {
        requireFeature(request);
        if (!"AUTHORITY".equals(body.provider()) || body.enabled() == null)
            throw new BusinessException(400, "请选择授权中心实名服务并设置开关");
        return R.ok(settings.saveIdentity(body.enabled()));
    }

    @GetMapping("/account")
    public R<JsonNode> account(HttpServletRequest request) {
        requireFeature(request);
        return R.ok(identity.account());
    }

    @PostMapping("/packages/{id}/checkout")
    public R<JsonNode> checkout(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(identity.checkout(id));
    }
}
