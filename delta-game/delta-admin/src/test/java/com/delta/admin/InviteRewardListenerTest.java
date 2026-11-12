package com.delta.admin;

import com.delta.admin.listener.InviteRewardListener;
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
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE invite_relation(id BIGINT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE invite_reward(id BIGINT AUTO_INCREMENT PRIMARY KEY,inviter_id BIGINT,invitee_id BIGINT,order_id BIGINT,amount DECIMAL(12,2),status VARCHAR(16),UNIQUE(invitee_id))");
        jdbc.execute("CREATE TABLE wallet(id BIGINT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(12,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE `transaction`(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(32),user_type VARCHAR(16),user_id BIGINT,amount DECIMAL(12,2),balance_before DECIMAL(12,2),balance_after DECIMAL(12,2),related_order_id BIGINT,remark VARCHAR(255))");
        jdbc.update("INSERT INTO `order` VALUES(100,7)");
        jdbc.update("INSERT INTO invite_relation VALUES(1,1,7,'PENDING')");
        jdbc.update("INSERT INTO wallet VALUES(1,1,0,CURRENT_TIMESTAMP)");
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
}
