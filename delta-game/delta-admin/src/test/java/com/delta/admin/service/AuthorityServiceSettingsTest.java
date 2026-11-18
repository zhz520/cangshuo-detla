package com.delta.admin.service;

import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorityServiceSettingsTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void savesSelfManagedAliyunWithoutCallingTheAuthorityGateway() {
        SysConfigService config = mock(SysConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SmsGatewayClient sms = mock(SmsGatewayClient.class);
        IdentityBillingClient identity = mock(IdentityBillingClient.class);
        when(sms.provider()).thenReturn("ALIYUN");
        when(sms.legacyConfigured()).thenReturn(true);
        when(sms.enabled()).thenReturn(true);
        when(config.getConfigValue("sms.aliyun.otp_enabled", "false")).thenReturn("true");
        when(sms.legacySettings()).thenReturn(
                new SmsGatewayClient.LegacySettings("dysmsapi.aliyuncs.com", "LTAI-old", true, "KK", "SMS_OLD", "", "", ""),
                new SmsGatewayClient.LegacySettings("dysmsapi.aliyuncs.com", "LTAI-new", true, "KK", "SMS_NEW", "", "", ""));

        AuthorityServiceSettings settings = new AuthorityServiceSettings(config, jdbc, sms, identity);
        TransactionSynchronizationManager.initSynchronization();
        AuthorityServiceSettings.SmsSettings result = settings.saveSms("ALIYUN", true, true,
                "dysmsapi.aliyuncs.com", "LTAI-new", "", "KK", "SMS_NEW", "", "", "");

        assertThat(result.provider()).isEqualTo("ALIYUN");
        assertThat(result.configured()).isTrue();
        assertThat(result.enabled()).isTrue();
        assertThat(result.otpEnabled()).isTrue();
        assertThat(result.verificationTemplateCode()).isEqualTo("SMS_NEW");
        verify(sms, never()).account();
        verify(config).evictCache("sms.provider.mode");
        verify(config).evictCache("sms.aliyun.otp_enabled");
    }
}
