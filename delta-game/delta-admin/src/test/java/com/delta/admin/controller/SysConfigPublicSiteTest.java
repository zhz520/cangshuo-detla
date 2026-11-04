package com.delta.admin.controller;

import com.delta.system.controller.SysConfigController;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysConfigPublicSiteTest {
    @Test
    void h5PaymentStaysDisabledWhenItsDeploymentSettingIsAbsent() {
        SysConfigService service = mock(SysConfigService.class);
        when(service.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<com.delta.system.entity.SysConfig>>any()))
                .thenReturn(List.of());
        when(service.getConfigValue(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        Map<String, String> site = new SysConfigController(service).getSiteConfig().getData();
        assertThat(site).containsEntry("wx_pay_h5_enabled", "false");
    }

    @Test
    void exposesIndependentWechatPaymentSceneSwitchesWithoutSecrets() {
        SysConfigService service = mock(SysConfigService.class);
        when(service.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<com.delta.system.entity.SysConfig>>any()))
                .thenReturn(List.of());
        Map<String, String> values = Map.of(
                "wx.pay.enabled", "true",
                "wx.pay.miniapp-enabled", "true",
                "wx.pay.h5-enabled", "false",
                "wx.pay.official-enabled", "true",
                "wx.pay.official-appid", "wx-official",
                "wx.pay.official-appsecret", "secret",
                "wx.pay.official-return-origin", "https://shop.example");
        when(service.getConfigValue(anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(0), fallback = call.getArgument(1);
            return values.getOrDefault(key, fallback);
        });
        Map<String, String> site = new SysConfigController(service).getSiteConfig().getData();
        assertThat(site).containsEntry("wx_pay_enabled", "true")
                .containsEntry("wx_pay_miniapp_enabled", "true")
                .containsEntry("wx_pay_h5_enabled", "false")
                .containsEntry("wx_pay_official_enabled", "true")
                .doesNotContainKeys("wx.pay.official-appid", "wx.pay.official-appsecret");
    }
}
