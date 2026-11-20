package com.delta.pay.service.impl;

import com.delta.common.exception.BusinessException;
import com.delta.pay.entity.Payment;
import com.wechat.pay.java.service.refund.model.Amount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class RepeatedRefundNotificationTest {

    private final PaymentServiceImpl service = mock(PaymentServiceImpl.class, CALLS_REAL_METHODS);

    @Test
    void completedRefundAcceptsMissingHistoricalTransactionId() {
        Payment payment = completedPayment();
        payment.setWxTransactionId(null);

        assertThatCode(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-not-stored", amount(100L, 100L)))
                .doesNotThrowAnyException();
    }

    @Test
    void completedRefundAcceptsRepeatedNotificationWithMatchingStoredTransactionId() {
        Payment payment = completedPayment();
        payment.setWxTransactionId("wx-transaction-1");

        assertThatCode(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-1", amount(100L, 100L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validateRefundNotification(
                payment, "refund-1", null, amount(100L, 100L)))
                .doesNotThrowAnyException();
    }

    @Test
    void completedRefundAcceptsNotificationWithMissingCurrency() {
        Payment payment = completedPayment();
        payment.setWxTransactionId("wx-transaction-1");
        Amount amount = amount(100L, 100L);
        amount.setCurrency(null);

        assertThatCode(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-1", amount))
                .doesNotThrowAnyException();
    }

    @Test
    void completedRefundRejectsNonCnyCurrency() {
        Payment payment = completedPayment();
        payment.setWxTransactionId("wx-transaction-1");
        Amount amount = amount(100L, 100L);
        amount.setCurrency("USD");

        assertThatThrownBy(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-1", amount))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void completedRefundStillRejectsConflictingTransactionIdOrAmounts() {
        Payment payment = completedPayment();
        payment.setWxTransactionId("wx-transaction-1");

        assertThatThrownBy(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-2", amount(100L, 100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-1", amount(100L, 99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void pendingRefundStillRequiresMatchingTransactionId() {
        Payment payment = completedPayment();
        payment.setStatus("REFUNDING");
        payment.setWxTransactionId("wx-transaction-1");

        assertThatThrownBy(() -> service.validateRefundNotification(
                payment, "refund-1", null, amount(100L, 100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void pendingRefundAcceptsMatchingNotification() {
        Payment payment = completedPayment();
        payment.setStatus("REFUNDING");
        payment.setWxTransactionId("wx-transaction-1");

        assertThatCode(() -> service.validateRefundNotification(
                payment, "refund-1", "wx-transaction-1", amount(100L, 100L)))
                .doesNotThrowAnyException();
    }

    private static Payment completedPayment() {
        Payment payment = new Payment();
        payment.setPayMethod("WECHAT");
        payment.setStatus("REFUNDED");
        payment.setRefundNo("refund-1");
        payment.setAmount(new BigDecimal("1.00"));
        payment.setRefundAmount(new BigDecimal("1.00"));
        return payment;
    }

    private static Amount amount(long total, long refund) {
        Amount amount = new Amount();
        amount.setTotal(total);
        amount.setRefund(refund);
        amount.setCurrency("CNY");
        return amount;
    }
}
