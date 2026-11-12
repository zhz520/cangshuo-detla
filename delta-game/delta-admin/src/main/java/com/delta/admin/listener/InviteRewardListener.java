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
        var orders = jdbc.queryForList("SELECT user_id FROM `order` WHERE id=?", event.getOrderId());
        if (orders.isEmpty()) return;
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
    }
}
