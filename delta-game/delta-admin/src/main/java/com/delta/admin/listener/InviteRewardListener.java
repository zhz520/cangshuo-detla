package com.delta.admin.listener;

import com.delta.common.event.OrderConfirmedEvent;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class InviteRewardListener {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onConfirmed(OrderConfirmedEvent event) {
        if (!"true".equalsIgnoreCase(configs.getConfigValue("marketing.invite.enabled", "false"))) return;
        var orders = jdbc.queryForList("SELECT user_id,amount FROM `order` WHERE id=?", event.getOrderId());
        if (orders.isEmpty()) return;
        java.math.BigDecimal orderAmount = (java.math.BigDecimal) orders.get(0).get("amount");
        if (orderAmount == null || orderAmount.signum() <= 0) return;
        Long invitee = ((Number) orders.get(0).get("user_id")).longValue();
        var relations = jdbc.queryForList("SELECT id,inviter_id FROM invite_relation WHERE invitee_id=? AND status='PENDING' FOR UPDATE", invitee);
        if (relations.isEmpty()) return;
        Long relationId = ((Number) relations.get(0).get("id")).longValue();
        Long inviter = ((Number) relations.get(0).get("inviter_id")).longValue();
        BigDecimal amount;
        try { amount = new BigDecimal(configs.getConfigValue("marketing.invite.reward_amount", "10")); }
        catch (Exception error) { amount = new BigDecimal("10"); }
        if (amount.signum() <= 0) return;
        try {
            jdbc.update("INSERT INTO invite_reward(inviter_id,invitee_id,order_id,amount,status) VALUES(?,?,?,?,'PENDING')",
                    inviter, invitee, event.getOrderId(), amount);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return;
        }
        jdbc.update("UPDATE invite_relation SET status='REWARDED' WHERE id=?", relationId);
        var wallets = jdbc.queryForList("SELECT id,balance FROM wallet WHERE user_id=? FOR UPDATE", inviter);
        java.math.BigDecimal before;
        if (wallets.isEmpty()) {
            try {
                jdbc.update("INSERT INTO wallet(user_id,balance,frozen_amount) VALUES(?,0,0)", inviter);
                before = java.math.BigDecimal.ZERO;
            } catch (org.springframework.dao.DuplicateKeyException race) {
                before = jdbc.queryForObject("SELECT balance FROM wallet WHERE user_id=? FOR UPDATE", java.math.BigDecimal.class, inviter);
                if (before == null) before = java.math.BigDecimal.ZERO;
            }
        } else {
            before = (java.math.BigDecimal) wallets.get(0).get("balance");
        }
        if (before == null) before = java.math.BigDecimal.ZERO;
        java.math.BigDecimal after = before.add(amount);
        jdbc.update("UPDATE wallet SET balance=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", after, inviter);
        jdbc.update("INSERT INTO `transaction`(type,user_type,user_id,amount,balance_before,balance_after,related_order_id,remark) VALUES('INVITE_REWARD','USER',?,?,?,?,?,?)",
                inviter, amount, before, after, event.getOrderId(), "邀请首单奖励");
        jdbc.update("UPDATE invite_reward SET status='PAID' WHERE order_id=? AND status='PENDING'", event.getOrderId());
    }
}
