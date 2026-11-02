package com.delta.pay.config;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
