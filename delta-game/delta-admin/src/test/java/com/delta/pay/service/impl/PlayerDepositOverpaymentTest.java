package com.delta.pay.service.impl;

import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.pay.entity.Payment;
import com.delta.pay.service.TransactionService;
import com.delta.pay.service.WechatPayScene;
import com.delta.product.service.ProductService;
import com.delta.system.service.SysConfigService;
import com.delta.user.service.WalletService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerDepositOverpaymentTest {
    private PaymentServiceImpl service(SysConfigService configs, WxPayConfiguration wx, JdbcTemplate jdbc) {
        return spy(new PaymentServiceImpl(mock(TransactionService.class), mock(OrderService.class),
                mock(OrderProgressService.class), mock(WalletService.class), mock(ProductService.class), configs, wx,
                mock(StringRedisTemplate.class), mock(ApplicationEventPublisher.class), jdbc));
    }

    @Test
    void remoteClosedOrderIsSynchronizedWithoutCallingCloseTwice() {
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = service(configs, wx, jdbc);
        doReturn(true).when(service).updateById(any(Payment.class));
        JsapiServiceExtension jsapi = mock(JsapiServiceExtension.class);
        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        when(clients.getJsapiService()).thenReturn(jsapi);
        Payment payment = new Payment();
        payment.setId(10L);
        payment.setPaymentNo("already-closed");
        Transaction remote = new Transaction();
        remote.setTradeState(Transaction.TradeStateEnum.CLOSED);
        when(jsapi.queryOrderByOutTradeNo(any(QueryOrderByOutTradeNoRequest.class))).thenReturn(remote);

        assertThat(service.resolvePendingPayment(payment, clients, true))
                .isEqualTo(PaymentServiceImpl.PendingPaymentResolution.CLOSED);
        verify(jsapi, never()).closeOrder(any());
        ArgumentCaptor<Payment> update = ArgumentCaptor.forClass(Payment.class);
        verify(service).updateById(update.capture());
        assertThat(update.getValue().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void reconciliationLeavesAnUnpaidOrderOpenButSceneSwitchClosesIt() {
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = service(configs, wx, jdbc);
        JsapiServiceExtension jsapi = mock(JsapiServiceExtension.class);
        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        when(clients.getJsapiService()).thenReturn(jsapi);
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setPaymentNo("still-unpaid");
        Transaction remote = new Transaction();
        remote.setTradeState(Transaction.TradeStateEnum.NOTPAY);
        when(jsapi.queryOrderByOutTradeNo(any(QueryOrderByOutTradeNoRequest.class))).thenReturn(remote);

        assertThat(service.resolvePendingPayment(payment, clients, false))
                .isEqualTo(PaymentServiceImpl.PendingPaymentResolution.PROCESSING);
        verify(jsapi, never()).closeOrder(any());
    }

    @Test
    void orderClosedRaceDuringCloseIsTreatedAsAClosedLocalPayment() throws Exception {
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = service(configs, wx, jdbc);
        doReturn(true).when(service).updateById(any(Payment.class));
        JsapiServiceExtension jsapi = mock(JsapiServiceExtension.class);
        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        when(clients.getJsapiService()).thenReturn(jsapi);
        Payment payment = new Payment();
        payment.setId(12L);
        payment.setPaymentNo("close-race");
        Transaction remote = new Transaction();
        remote.setTradeState(Transaction.TradeStateEnum.NOTPAY);
        when(jsapi.queryOrderByOutTradeNo(any(QueryOrderByOutTradeNoRequest.class))).thenReturn(remote);
        ServiceException race = mock(ServiceException.class);
        when(race.getErrorCode()).thenReturn("ORDER_CLOSED");
        doThrow(race).when(jsapi).closeOrder(any(CloseOrderRequest.class));

        assertThat(service.resolvePendingPayment(payment, clients, true))
                .isEqualTo(PaymentServiceImpl.PendingPaymentResolution.CLOSED);
        ArgumentCaptor<Payment> update = ArgumentCaptor.forClass(Payment.class);
        verify(service).updateById(update.capture());
        assertThat(update.getValue().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void switchingSceneClosesTheOtherPendingDepositBeforeCreatingANewOne() {
        TransactionService transactions = mock(TransactionService.class);
        OrderService orders = mock(OrderService.class);
        OrderProgressService progress = mock(OrderProgressService.class);
        WalletService wallets = mock(WalletService.class);
        ProductService products = mock(ProductService.class);
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = spy(new PaymentServiceImpl(transactions, orders, progress, wallets, products,
                configs, wx, redis, events, jdbc));

        Payment oldPayment = new Payment();
        oldPayment.setId(8L);
        oldPayment.setPaymentNo("deposit-mini-old");
        oldPayment.setUserId(7L);
        oldPayment.setBizType("PLAYER_DEPOSIT");
        oldPayment.setAmount(new BigDecimal("100.00"));
        oldPayment.setPayMethod(WechatPayScene.MINIAPP.payMethod());
        oldPayment.setWxAppId("wx-mini");
        oldPayment.setStatus("PAYING");
        doReturn(List.of(oldPayment)).when(service).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        doReturn(null).when(service).getOne(any());
        doReturn(true).when(service).save(any(Payment.class));
        doReturn(true).when(service).updateById(any(Payment.class));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 7L)));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        when(configs.getConfigValue("player.deposit_required", "true")).thenReturn("true");
        when(configs.getConfigValue("player.deposit_mode", "REQUIRED")).thenReturn("REQUIRED");
        when(configs.getConfigValue("player.deposit_amount", "100")).thenReturn("100");

        JsapiServiceExtension jsapi = mock(JsapiServiceExtension.class);
        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        when(clients.getJsapiService()).thenReturn(jsapi);
        when(wx.getClients()).thenReturn(clients);
        Transaction remote = new Transaction();
        remote.setTradeState(Transaction.TradeStateEnum.NOTPAY);
        when(jsapi.queryOrderByOutTradeNo(any(QueryOrderByOutTradeNoRequest.class))).thenReturn(remote);

        Payment created = service.createPlayerDepositPayment(7L, WechatPayScene.H5, "test-app");

        assertThat(created.getPayMethod()).isEqualTo(WechatPayScene.H5.payMethod());
        assertThat(created.getWxAppId()).isEqualTo("test-app");
        ArgumentCaptor<CloseOrderRequest> close = ArgumentCaptor.forClass(CloseOrderRequest.class);
        verify(jsapi).closeOrder(close.capture());
        assertThat(close.getValue().getOutTradeNo()).isEqualTo("deposit-mini-old");
        ArgumentCaptor<Payment> updated = ArgumentCaptor.forClass(Payment.class);
        verify(service).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(8L);
        assertThat(updated.getValue().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void aSecondSuccessfulDepositIsRefundedInsteadOfPermanentlyOvercharging() {
        TransactionService transactions = mock(TransactionService.class);
        OrderService orders = mock(OrderService.class);
        OrderProgressService progress = mock(OrderProgressService.class);
        WalletService wallets = mock(WalletService.class);
        ProductService products = mock(ProductService.class);
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = spy(new PaymentServiceImpl(transactions, orders, progress, wallets, products,
                configs, wx, redis, events, jdbc));

        Payment payment = new Payment();
        payment.setId(9L);
        payment.setPaymentNo("deposit-second");
        payment.setUserId(7L);
        payment.setBizType("PLAYER_DEPOSIT");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setPayMethod(WechatPayScene.OFFICIAL_ACCOUNT.payMethod());
        payment.setWxAppId("wx-official");
        payment.setStatus("PAYING");
        doReturn(payment).when(service).getOne(any());
        doReturn(0L).when(service).count(any());
        List<String> updateStatuses = new ArrayList<>();
        doAnswer(invocation -> {
            updateStatuses.add(((Payment) invocation.getArgument(0)).getStatus());
            return true;
        }).when(service).updateById(any(Payment.class));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 7L)));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100.00"));
        when(configs.getConfigValue("player.deposit_required", "true")).thenReturn("true");
        when(configs.getConfigValue("player.deposit_mode", "REQUIRED")).thenReturn("REQUIRED");
        when(configs.getConfigValue("player.deposit_amount", "100")).thenReturn("100");

        WxPayConfiguration.CachedClients clients = mock(WxPayConfiguration.CachedClients.class);
        when(clients.getConfig()).thenReturn(new WxPayConfiguration.RuntimeConfig(true, "test-app", "test-merchant",
                "https://shop.example/pay/wx/notify", null, null, null, null, null, "WAP", "Test shop"));
        Transaction transaction = new Transaction();
        transaction.setOutTradeNo("deposit-second");
        transaction.setTransactionId("wx-second-transaction");
        transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        transaction.setAppid("wx-official");
        transaction.setMchid("test-merchant");
        TransactionAmount amount = new TransactionAmount();
        amount.setTotal(10000);
        amount.setCurrency("CNY");
        transaction.setAmount(amount);

        service.applyVerifiedTransaction(transaction, clients);

        assertThat(updateStatuses).containsExactly("PAID", "REFUNDING");
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("100.00");
    }
}
