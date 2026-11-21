package com.delta.identity.controller;

import com.alibaba.fastjson2.JSONObject;
import com.delta.identity.service.AuthorityServiceSettings;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.sms.service.SmsGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-level provider preference, account access and self-managed Aliyun settings. */
@RestController
@RequestMapping("/admin/sms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
public class SmsGatewayAdminController {
    private final SmsGatewayClient smsGatewayClient;
    private final AuthorityServiceSettings settings;

    public record SettingsRequest(String provider, Boolean enabled, Boolean otpEnabled, String endpoint,
                                  String accessKeyId, String accessKeySecret, String signName,
                                  String verificationTemplateCode, String playerFinishTemplateCode,
                                  String csMessageTemplateCode, String teammateInvitedTemplateCode) {}

    @GetMapping("/settings")
    public R<AuthorityServiceSettings.SmsSettings> settings() { return R.ok(settings.sms()); }

    @PutMapping("/settings")
    @OpLog(module="system", operation="修改短信服务设置")
    public R<AuthorityServiceSettings.SmsSettings> updateSettings(@RequestBody SettingsRequest body) {
        if (body.enabled() == null || body.otpEnabled() == null)
            throw new BusinessException(400, "请设置短信服务和验证码开关");
        return R.ok(settings.saveSms(body.provider(), body.enabled(), body.otpEnabled(), body.endpoint(),
                body.accessKeyId(), body.accessKeySecret(), body.signName(), body.verificationTemplateCode(),
                body.playerFinishTemplateCode(), body.csMessageTemplateCode(), body.teammateInvitedTemplateCode()));
    }

    @GetMapping("/account")
    public R<JSONObject> account() {
        return R.ok(smsGatewayClient.account());
    }

    @PostMapping("/packages/{packageId}/checkout")
    public R<JSONObject> checkoutPackage(@PathVariable String packageId) {
        return R.ok(smsGatewayClient.createPackageCheckout(packageId));
    }
}
