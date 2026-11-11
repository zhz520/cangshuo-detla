package com.delta.pay.service;

import com.delta.pay.config.WxPayConfiguration;
import com.delta.pay.entity.Payment;
import com.delta.system.service.SysConfigService;
import com.delta.common.redis.service.RedisService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxPaymentRecoveryTaskTest {

    @Test
    void expiredPayingOrderIsReconciledWithPermissionToCloseUnpaid() {
        PaymentService payments = mock(PaymentService.class);
        WxPayConfiguration configuration = mock(WxPayConfiguration.class);
        SysConfigService configs = mock(SysConfigService.class);
        RedisService redis = mock(RedisService.class);
        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);

        when(configuration.isEnabled()).thenReturn(true);
        when(configuration.getClients()).thenReturn(clients);
        when(redis.tryLock("task:wx-payment-recovery", 5, java.util.concurrent.TimeUnit.MINUTES)).thenReturn(true);
        when(configs.getConfigValue("wx.pay.pending-close-hours", "24")).thenReturn("24");

        Payment old = new Payment();
        old.setId(17L);
        old.setStatus("PAYING");
        old.setPayMethod("WECHAT_OFFICIAL");
        old.setCreatedAt(LocalDateTime.now().minusHours(48));
        when(payments.list(any(Wrapper.class))).thenReturn(List.of()).thenReturn(List.of(old));

        WxPaymentRecoveryTask task = new WxPaymentRecoveryTask(payments, configuration, configs, redis);
        task.recover();

        verify(payments).reconcilePayment(eq(17L), eq(true));
        verify(redis).unlock("task:wx-payment-recovery");
    }
}
