package com.delta.user.controller;

import com.delta.common.domain.R;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public, non-sensitive description of the enabled web login methods. */
@RestController
@RequestMapping("/user/auth")
@RequiredArgsConstructor
public class UserLoginPolicyController {
    private final SysConfigService configs;

    @GetMapping("/login-policy")
    public R<Map<String, Object>> policy() {
        boolean phone = enabled("auth.phone.enabled", true);
        boolean password = enabled("auth.password.enabled", true);
        boolean wechatRequested = enabled("auth.wechat.enabled", false);
        boolean wechatConfigured = !configs.getConfigValue("wechat.official.app_id", "").trim().isBlank()
                && !configs.getConfigValue("wechat.official.app_secret", "").trim().isBlank();
        boolean wechat = wechatRequested && wechatConfigured;
        String requestedPrimary = configs.getConfigValue("auth.primary_method", "PHONE").trim().toUpperCase();
        String primary = "WECHAT".equals(requestedPrimary) && wechat ? "WECHAT"
                : phone ? "PHONE" : password ? "PASSWORD" : wechat ? "WECHAT" : "NONE";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primaryMethod", primary);
        result.put("phoneEnabled", phone);
        result.put("passwordEnabled", password);
        result.put("wechatEnabled", wechat);
        result.put("available", phone || password || wechat);
        result.put("reason", primary.equals("NONE") ? "所有网页登录方式均已关闭，请联系管理员" : "");
        return R.ok(result);
    }

    private boolean enabled(String key, boolean fallback) {
        return "true".equalsIgnoreCase(configs.getConfigValue(key, Boolean.toString(fallback)));
    }
}
