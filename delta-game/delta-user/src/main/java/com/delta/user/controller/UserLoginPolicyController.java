package com.delta.user.controller;

import com.delta.common.domain.R;
import com.delta.user.service.WebLoginPolicy;
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
    private final WebLoginPolicy login;

    @GetMapping("/login-policy")
    public org.springframework.http.ResponseEntity<R<Map<String, Object>>> policyResponse() {
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(policy());
    }

    public R<Map<String, Object>> policy() {
        boolean phone = login.phoneEnabled();
        boolean password = login.passwordEnabled();
        boolean wechat = login.wechatEnabled();
        boolean fallback = "WECHAT".equals(login.primary()) && !wechat;
        String primary = fallback ? "PHONE" : login.primary();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primaryMethod", primary);
        result.put("phoneEnabled", phone);
        result.put("passwordEnabled", password);
        result.put("wechatEnabled", wechat);
        result.put("available", phone || password || wechat);
        result.put("reason", fallback ? "微信登录暂不可用，已切换到手机号登录" : "");
        return R.ok(result);
    }

}
