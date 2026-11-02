package com.delta.admin.pay;

import com.delta.common.exception.BusinessException;
import com.delta.pay.service.WechatOfficialPayService;
import com.delta.system.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WechatOfficialPayServiceTest {
    @Mock SysConfigService configs;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    private WechatOfficialPayService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        service = new WechatOfficialPayService(configs, redis, new ObjectMapper());
    }

    @Test
    void buildsSnsapiBaseAuthorizationUrlWithConfiguredOfficialAccount() {
        configure("https://game.example.com/", true);
        String url = service.authorizationUrl("P202611020001", 42L, "ORDER", "/pages/order/detail?id=7");
        assertThat(url).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize?");
        assertThat(url).contains("appid=wx-official");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fgame.example.com%2Fapi%2Fpay%2Fwx%2Fofficial%2Fcallback");
        assertThat(url).contains("scope=snsapi_base");
        assertThat(url).endsWith("#wechat_redirect");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), value.capture(), eq(Duration.ofMinutes(10)));
        assertThat(key.getValue()).matches("wxpay:official:state:[a-f0-9]{64}");
        assertThat(value.getValue()).contains("P202611020001").contains("\"userId\":42").contains("ORDER");
    }

    @Test
    void callbackPageIncludesBridgeParametersAndConfiguredReturnUrl() {
        String html = service.paymentPage("wx-official", Map.of(
                "timeStamp", "1", "nonceStr", "n", "package", "prepay_id=p", "signType", "RSA", "paySign", "s"),
                "https://game.example.com/#/pages/order/detail?id=7");
        assertThat(html).contains("WeixinJSBridge.invoke('getBrandWCPayRequest'").contains("prepay_id=p")
                .contains("https://game.example.com/#/pages/order/detail?id=7").contains("get_brand_wcpay_request:ok");
    }

    @Test
    void originMustBeAnHttpsOriginWithoutPath() {
        assertThat(WechatOfficialPayService.normalizeOrigin("https://game.example.com/")).isEqualTo("https://game.example.com");
        assertThatThrownBy(() -> WechatOfficialPayService.normalizeOrigin("http://game.example.com")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WechatOfficialPayService.normalizeOrigin("https://game.example.com/pay")).isInstanceOf(BusinessException.class);
    }

    @Test
    void incompleteOfficialAccountConfigurationCannotStartPayment() {
        configure("", false);
        assertThatThrownBy(() -> service.authorizationUrl("P1", 1L, "ORDER", "/pages/order/detail?id=1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        assertThatThrownBy(() -> service.exchange("code", "0".repeat(64))).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(503));
    }

    private void configure(String origin, boolean enabled) {
        when(configs.getConfigValue("wx.pay.official-return-origin", "")).thenReturn(origin);
        when(configs.getConfigValue("wx.pay.official-enabled", "false")).thenReturn(Boolean.toString(enabled));
        when(configs.getConfigValue("wx.pay.official-appid", "")).thenReturn("wx-official");
        when(configs.getConfigValue("wx.pay.official-appsecret", "")).thenReturn("test-secret");
    }
}
