package com.delta.pay.config;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class WxPayConfigurationTest {
    @Test
    void enabledPaymentWithMissingCertificateFilesExplainsWhatToUpload() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("wx.pay.enabled")).thenReturn("true");
        WxPayConfiguration configuration = new WxPayConfiguration(configs);

        assertThatThrownBy(configuration::getClients)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商户证书缺失或无效")
                .hasMessageContaining("商户私钥和微信支付公钥");
    }

    @Test
    void disabledPaymentDoesNotBuildAClient() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("wx.pay.enabled")).thenReturn("false");

        assertThat(new WxPayConfiguration(configs).getClients()).isNull();
    }

    @Test
    void sharedModeLoadsPaymentConfigurationForTheCurrentTenantOnly() throws Exception {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue(anyString())).thenAnswer(invocation -> {
            String key = (String) invocation.getArgument(0);
            if ("wx.pay.enabled".equals(key)) return "true";
            if ("wx.pay.appid".equals(key)) return "app-" + TenantContext.requireId();
            if ("wx.pay.mch-id".equals(key)) return "mch-" + TenantContext.requireId();
            return null;
        });
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        WxPayConfiguration configuration = new WxPayConfiguration(configs);
        ReflectionTestUtils.setField(configuration, "tenantProperties", properties);

        var tenantA = TenantContext.callAs("tenant-a", configuration::getRuntimeConfig);
        var tenantB = TenantContext.callAs("tenant-b", configuration::getRuntimeConfig);

        assertThat(tenantA.mchId()).isEqualTo("mch-tenant-a");
        assertThat(tenantB.mchId()).isEqualTo("mch-tenant-b");
        assertThatThrownBy(configuration::getRuntimeConfig)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户上下文");
    }
}
