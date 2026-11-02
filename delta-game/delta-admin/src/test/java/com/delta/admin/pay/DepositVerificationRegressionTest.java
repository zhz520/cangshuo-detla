package com.delta.admin.pay;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.delta.pay.entity.Payment;
import com.delta.pay.service.impl.PaymentServiceImpl;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DepositVerificationRegressionTest {
    PaymentServiceImpl service;
    JdbcTemplate jdbc;
    SysConfigService configs;
    Payment receipt;

    @BeforeEach void setup() {
        configs = mock(SysConfigService.class);
        jdbc = mock(JdbcTemplate.class);
        when(configs.getConfigValue(anyString(), anyString())).thenAnswer(a -> a.getArgument(1));
        service = spy(new PaymentServiceImpl(null, null, null, null, null, configs, null, null, null, jdbc));
        receipt = new Payment();
        receipt.setPaymentNo("deposit-receipt");
        receipt.setUserId(7L);
        receipt.setBizType("PLAYER_DEPOSIT");
        receipt.setStatus("PAID");
        doReturn(receipt).when(service).getOne(any(Wrapper.class));
    }

    void paid(String amount) {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal(amount));
    }

    @Test void applicationAcceptsTheTopUpReceiptOnceCumulativeDepositsMeetTheRequirement() {
        receipt.setAmount(new BigDecimal("40"));
        paid("100"); // An earlier 60 plus the latest top-up of 40.
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isTrue();
    }

    @Test void loweringTheRequirementDoesNotInvalidateAnAlreadySufficientDeposit() {
        when(configs.getConfigValue("player.deposit_amount", "100")).thenReturn("60");
        receipt.setAmount(new BigDecimal("100"));
        paid("100");
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isTrue();
    }

    @Test void insufficientAggregateDepositsRemainRejected() {
        receipt.setAmount(new BigDecimal("40"));
        paid("40");
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isFalse();
    }

    @Test void cumulativeFundsDoNotMakeAnotherUsersOrUnpaidOrUnrelatedReceiptValid() {
        receipt.setAmount(new BigDecimal("100"));
        paid("100");
        receipt.setUserId(8L);
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isFalse();
        receipt.setUserId(7L);
        receipt.setStatus("PAYING");
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isFalse();
        receipt.setStatus("PAID");
        receipt.setBizType("RECHARGE");
        assertThat(service.verifyPlayerDepositPayment(receipt.getPaymentNo(), 7L)).isFalse();
    }
}
