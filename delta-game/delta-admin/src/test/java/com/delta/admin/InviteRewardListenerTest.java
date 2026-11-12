package com.delta.admin;

import com.delta.admin.listener.InviteRewardListener;
import com.delta.admin.listener.InviteRewardRefundListener;
import com.delta.common.event.BusinessEvent;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InviteRewardListenerTest {
    @Test
    void firstConfirmedOrderCreditsInviterOnce() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:invite-reward-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE invite_relation(id BIGINT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE invite_reward(id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,order_id BIGINT,amount DECIMAL(12,2),status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(12,2),frozen_amount DECIMAL(12,2) DEFAULT 0,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `transaction`(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(32),user_type VARCHAR(16),user_id BIGINT,amount DECIMAL(12,2),balance_before DECIMAL(12,2),balance_after DECIMAL(12,2),related_order_id BIGINT,remark VARCHAR(255))");
        jdbc.update("INSERT INTO `order` VALUES(100,7,100.00)");
        jdbc.update("INSERT INTO invite_relation VALUES(1,1,7,'PENDING')");
        jdbc.update("INSERT INTO wallet(user_id,balance,updated_at) VALUES(1,0,CURRENT_TIMESTAMP)");
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("marketing.invite.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue("marketing.invite.reward_amount", "10")).thenReturn("10");
        InviteRewardListener listener = new InviteRewardListener(jdbc, configs);

        listener.onConfirmed(new OrderConfirmedEvent(this, 100L, "SYSTEM"));
        listener.onConfirmed(new OrderConfirmedEvent(this, 100L, "SYSTEM"));

        assertThat(jdbc.queryForObject("SELECT balance FROM wallet WHERE user_id=1", BigDecimal.class)).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT status FROM invite_reward WHERE invitee_id=7", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `transaction`", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingWalletIsCreatedBeforeCreditingTheReward() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:invite-wallet-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE invite_relation(id BIGINT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE invite_reward(id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,order_id BIGINT,amount DECIMAL(12,2),status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(12,2),frozen_amount DECIMAL(12,2) DEFAULT 0,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `transaction`(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(32),user_type VARCHAR(16),user_id BIGINT,amount DECIMAL(12,2),balance_before DECIMAL(12,2),balance_after DECIMAL(12,2),related_order_id BIGINT,remark VARCHAR(255))");
        jdbc.update("INSERT INTO `order` VALUES(100,7,100.00)");
        jdbc.update("INSERT INTO invite_relation VALUES(1,1,7,'PENDING')");
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("marketing.invite.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue("marketing.invite.reward_amount", "10")).thenReturn("10");

        new InviteRewardListener(jdbc, configs).onConfirmed(new OrderConfirmedEvent(this, 100L, "SYSTEM"));

        assertThat(jdbc.queryForObject("SELECT balance FROM wallet WHERE user_id=1", BigDecimal.class)).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT status FROM invite_reward WHERE invitee_id=7", String.class)).isEqualTo("PAID");
    }

    @Test
    void fullRefundReversesRewardAndRecordsAnyUnrecoverableDebt() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:invite-refund-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE payment(order_id BIGINT,status VARCHAR(20),refund_amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE invite_relation(id BIGINT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE invite_reward(id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,order_id BIGINT,amount DECIMAL(12,2),status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE invite_reward_debt(id BIGINT AUTO_INCREMENT PRIMARY KEY,reward_id BIGINT,inviter_id BIGINT,amount DECIMAL(12,2),status VARCHAR(16),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,recovered_at TIMESTAMP NULL,UNIQUE(reward_id))");
        jdbc.execute("CREATE TABLE wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(12,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `transaction`(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(32),user_type VARCHAR(16),user_id BIGINT,amount DECIMAL(12,2),balance_before DECIMAL(12,2),balance_after DECIMAL(12,2),related_order_id BIGINT,remark VARCHAR(255))");
        jdbc.update("INSERT INTO `order` VALUES(100,7,100.00)");
        jdbc.update("INSERT INTO invite_relation VALUES(1,1,7,'REWARDED')");
        jdbc.update("INSERT INTO invite_reward(inviter_id,invitee_id,order_id,amount,status) VALUES(1,7,100,10,'PAID')");
        jdbc.update("INSERT INTO wallet(user_id,balance) VALUES(1,3.00)");
        jdbc.update("INSERT INTO payment VALUES(100,'REFUNDED',100.00)");

        new InviteRewardRefundListener(jdbc).onOrderRefunded(
                new BusinessEvent(this, "ORDER_REFUNDED", "USER", 7L, 100L, "全额退款"));

        assertThat(jdbc.queryForObject("SELECT balance FROM wallet WHERE user_id=1", BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT status FROM invite_reward WHERE invitee_id=7", String.class)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT amount FROM invite_reward_debt WHERE inviter_id=1", BigDecimal.class)).isEqualByComparingTo("7.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM `transaction` WHERE type='INVITE_REWARD_BACK'", BigDecimal.class)).isEqualByComparingTo("-3.00");
    }
}
