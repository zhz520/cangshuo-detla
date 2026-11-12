package com.delta.admin;

import com.delta.system.service.SysConfigService;
import com.delta.user.controller.UserLoginPolicyController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLoginPolicyControllerTest {
    @Mock SysConfigService configs;

    @Test
    void wechatCannotBecomePrimaryUntilBothCredentialsArePresent() {
        stub(false, "WECHAT", "", "");

        var policy = new UserLoginPolicyController(configs).policy().getData();

        assertThat(policy.get("primaryMethod")).isEqualTo("PHONE");
        assertThat(policy.get("wechatEnabled")).isEqualTo(false);
        assertThat(policy.get("available")).isEqualTo(true);
    }

    @Test
    void configuredWechatCanBePrimary() {
        stub(true, "WECHAT", "wx-public-app", "test-secret");

        var policy = new UserLoginPolicyController(configs).policy().getData();

        assertThat(policy.get("primaryMethod")).isEqualTo("WECHAT");
        assertThat(policy.get("wechatEnabled")).isEqualTo(true);
        assertThat(policy).doesNotContainKey("appSecret");
        assertThat(policy.values()).noneMatch(value -> String.valueOf(value).contains("test-secret"));
    }

    @Test
    void allDisabledReturnsAnExplicitUnavailablePolicy() {
        when(configs.getConfigValue("auth.wechat.enabled", "false")).thenReturn("false");
        when(configs.getConfigValue("auth.primary_method", "PHONE")).thenReturn("PHONE");
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn("");
        lenient().when(configs.getConfigValue("wechat.official.app_secret", "")).thenReturn("");
        when(configs.getConfigValue("auth.phone.enabled", "true")).thenReturn("false");
        when(configs.getConfigValue("auth.password.enabled", "true")).thenReturn("false");

        var policy = new UserLoginPolicyController(configs).policy().getData();

        assertThat(policy.get("primaryMethod")).isEqualTo("NONE");
        assertThat(policy.get("available")).isEqualTo(false);
        assertThat(String.valueOf(policy.get("reason"))).contains("已关闭");
    }

    private void stub(boolean wechatEnabled, String primary, String appId, String secret) {
        when(configs.getConfigValue("auth.wechat.enabled", "false")).thenReturn(Boolean.toString(wechatEnabled));
        when(configs.getConfigValue("auth.primary_method", "PHONE")).thenReturn(primary);
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn(appId);
        lenient().when(configs.getConfigValue("wechat.official.app_secret", "")).thenReturn(secret);
        when(configs.getConfigValue("auth.phone.enabled", "true")).thenReturn("true");
        when(configs.getConfigValue("auth.password.enabled", "true")).thenReturn("true");
    }
}
