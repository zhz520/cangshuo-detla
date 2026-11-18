package com.delta.admin;

import com.delta.system.service.SysConfigService;
import com.delta.user.controller.UserLoginPolicyController;
import com.delta.user.service.WebLoginPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserLoginPolicyControllerTest {
    final Map<String,String> settings = new HashMap<>();
    final SysConfigService configs = mock(SysConfigService.class, invocation -> {
        if (invocation.getMethod().getName().equals("getConfigValue"))
            return settings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1));
        return RETURNS_DEFAULTS.answer(invocation);
    });
    final UserLoginPolicyController controller = new UserLoginPolicyController(new WebLoginPolicy(configs));

    @Test
    void incompleteWechatFallsBackEvenForOldDisabledPhoneSetting() {
        settings.putAll(Map.of("auth.primary_method","WECHAT","auth.wechat.enabled","true",
                "auth.phone.enabled","false","auth.password.enabled","false"));
        assertThat(controller.policy().getData()).containsEntry("primaryMethod","PHONE")
                .containsEntry("phoneEnabled", true).containsEntry("wechatEnabled",false)
                .containsEntry("passwordEnabled",false).containsEntry("available",true);
        assertThat(controller.policy().getData().get("reason").toString()).contains("手机号登录");
    }

    @Test
    void switchTakesEffectWithoutRestartAndNeverDisclosesCredentials() {
        settings.putAll(Map.of("auth.primary_method","WECHAT","auth.wechat.enabled","true",
                "wechat.official.app_id","wx-test","wechat.official.app_secret","test-secret",
                "site.customer_web_url","https://shop.example.com","auth.phone.enabled","false"));
        var policy = controller.policy().getData();
        assertThat(policy).containsEntry("primaryMethod","WECHAT").containsEntry("wechatEnabled",true)
                .containsEntry("phoneEnabled",false);
        assertThat(policy.values()).noneMatch(value -> String.valueOf(value).contains("test-secret"));
        settings.put("auth.primary_method","PHONE");
        assertThat(controller.policy().getData()).containsEntry("primaryMethod","PHONE")
                .containsEntry("phoneEnabled",true).containsEntry("wechatEnabled",true);
    }
}
