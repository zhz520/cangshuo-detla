package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.pay.service.PaymentService;
import com.delta.player.service.PlayerDepositRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerDepositRefundServiceTest {
    JdbcTemplate jdbc;
    PaymentService payments;
    ApplicationEventPublisher events;
    PlayerDepositRefundService service;

    @BeforeEach void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:deposit-refund-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,openid VARCHAR(64),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,openid VARCHAR(64),nickname VARCHAR(64),phone VARCHAR(20),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player_wallet(player_id BIGINT PRIMARY KEY,balance DECIMAL(12,2),frozen_amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,payment_no VARCHAR(32),user_id BIGINT,biz_type VARCHAR(20),amount DECIMAL(10,2),status VARCHAR(20),paid_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE player_deposit_refund(id BIGINT PRIMARY KEY,refund_no VARCHAR(40),player_id BIGINT,user_id BIGINT," +
                "payment_id BIGINT,amount DECIMAL(10,2),reason VARCHAR(500),status VARCHAR(32),active_payment_id BIGINT," +
                "reviewer_id BIGINT,review_note VARCHAR(500),reviewed_at TIMESTAMP,channel_refund_no VARCHAR(64),last_error VARCHAR(500)," +
                "completed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,player_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE order_player(order_id BIGINT,player_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE player_income_settlement(player_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE withdraw(id BIGINT PRIMARY KEY,player_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE player_refund_recovery(id BIGINT PRIMARY KEY,player_id BIGINT,remaining DECIMAL(12,2))");
        jdbc.update("INSERT INTO `user` VALUES(7,'openid-7',0)");
        jdbc.update("INSERT INTO player(id,openid,nickname,deleted) VALUES(1,'openid-7','测试打手',0)");
        jdbc.update("INSERT INTO player_wallet VALUES(1,0,0)");
        jdbc.update("INSERT INTO payment(id,payment_no,user_id,biz_type,amount,status) VALUES(91,'P-DEPOSIT',7,'PLAYER_DEPOSIT',100.00,'PAID')");
        payments = mock(PaymentService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new PlayerDepositRefundService(jdbc, payments, events);
    }

    @Test void eligibleDepositCanBeSubmittedOnceAndCancelledByPlayer() {
        var context = service.context(1L);
        assertThat(context.payments()).hasSize(1);
        assertThat(context.payments().get(0).eligible()).isTrue();
        var refund = service.submit(1L, 91L, "申请退出平台，退回押金");
        assertThat(refund.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT active_payment_id FROM player_deposit_refund WHERE id=?", Long.class, refund.id()))
                .isEqualTo(91L);
        assertThatThrownBy(() -> service.submit(1L, 91L, "重复申请押金退款"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已有退款申请");
        service.cancel(1L, refund.id());
        assertThat(jdbc.queryForObject("SELECT status FROM player_deposit_refund WHERE id=?", String.class, refund.id()))
                .isEqualTo("CANCELLED");
    }

    @Test void pendingWithdrawalOrActiveOrderBlocksDepositRefund() {
        jdbc.update("INSERT INTO withdraw VALUES(5,1,'PENDING')");
        assertThat(service.context(1L).payments().get(0).eligible()).isFalse();
        jdbc.update("DELETE FROM withdraw");
        jdbc.update("INSERT INTO `order` VALUES(10,1,'IN_PROGRESS')");
        assertThat(service.context(1L).payments().get(0).blockReason()).contains("订单");
    }

    @Test void approvalRechecksNewOrdersForTeammatesAndUnreleasedIncome() {
        var refund = service.submit(1L, 91L, "申请退出平台，退回押金");
        jdbc.update("INSERT INTO `order` VALUES(10,2,'IN_PROGRESS')");
        jdbc.update("INSERT INTO order_player VALUES(10,1,'ACCEPTED')");
        assertThatThrownBy(() -> service.review(refund.id(), 9L, true, ""))
                .isInstanceOf(BusinessException.class).hasMessageContaining("订单");
        jdbc.update("DELETE FROM order_player");
        jdbc.update("INSERT INTO player_income_settlement VALUES(1,'PENDING')");
        assertThatThrownBy(() -> service.review(refund.id(), 9L, true, ""))
                .isInstanceOf(BusinessException.class).hasMessageContaining("待结算收益");
        verify(payments, never()).refundPlayerDeposit(any(), any(), any(), anyString());
    }

    @Test void foreignPaymentAndAmbiguousRefundCannotBeRetried() {
        jdbc.update("INSERT INTO payment(id,payment_no,user_id,biz_type,amount,status) VALUES(92,'P-OTHER',8,'PLAYER_DEPOSIT',100.00,'PAID')");
        assertThatThrownBy(() -> service.submit(1L, 92L, "申请退出平台，退回押金"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        var refund = service.submit(1L, 91L, "申请退出平台，退回押金");
        jdbc.update("UPDATE player_deposit_refund SET status='FAILED',last_error='ABNORMAL' WHERE id=?", refund.id());
        assertThatThrownBy(() -> service.retry(refund.id(), 9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("渠道已关闭");
        verify(payments, never()).refundPlayerDeposit(any(), any(), any(), anyString());
    }

    @Test void customerServiceApprovalStartsOriginalChannelRefund() {
        var refund = service.submit(1L, 91L, "申请退出平台，退回押金");
        doAnswer(inv -> {
            jdbc.update("UPDATE payment SET status='REFUNDING' WHERE id=91");
            return null;
        }).when(payments).refundPlayerDeposit(eq(91L), eq(7L), eq(new BigDecimal("100.00")), anyString());
        var reviewed = service.review(refund.id(), 9L, true, "核实无进行中订单");
        assertThat(reviewed.status()).isEqualTo("REFUNDING");
        assertThat(reviewed.reviewerId()).isEqualTo(9L);
        verify(payments).refundPlayerDeposit(91L, 7L, new BigDecimal("100.00"), "打手押金退款");
    }
}
