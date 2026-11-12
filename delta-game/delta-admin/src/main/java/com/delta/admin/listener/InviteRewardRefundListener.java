package com.delta.admin.listener;

import com.delta.common.event.BusinessEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Reverses a referral reward when the qualifying order is fully refunded. */
@Component
@RequiredArgsConstructor
public class InviteRewardRefundListener {
    private final JdbcTemplate jdbc;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onOrderRefunded(BusinessEvent event) {
        if (!"ORDER_REFUNDED".equals(event.getEventType()) || event.getOrderId() == null) return;
        Long orderId = event.getOrderId();
        var totals = jdbc.queryForList("SELECT o.amount,COALESCE(SUM(p.refund_amount),0) refunded FROM `order` o " +
                "LEFT JOIN payment p ON p.order_id=o.id WHERE o.id=? GROUP BY o.id,o.amount", orderId);
        if (totals.isEmpty()) return;
        BigDecimal amount = (BigDecimal) totals.get(0).get("amount");
        BigDecimal refunded = (BigDecimal) totals.get(0).get("refunded");
        if (amount == null || refunded == null || refunded.compareTo(amount) < 0) return;

        var rewards = jdbc.queryForList("SELECT id,inviter_id,invitee_id,amount,status FROM invite_reward " +
                "WHERE order_id=? FOR UPDATE", orderId);
        if (rewards.isEmpty()) return;
        var reward = rewards.get(0);
        String status = String.valueOf(reward.get("status"));
        if (!"PAID".equals(status) && !"PENDING".equals(status)) return;
        Long rewardId = ((Number) reward.get("id")).longValue();
        Long inviter = ((Number) reward.get("inviter_id")).longValue();
        Long invitee = ((Number) reward.get("invitee_id")).longValue();
        BigDecimal rewardAmount = (BigDecimal) reward.get("amount");

        if ("PENDING".equals(status)) {
            jdbc.update("UPDATE invite_reward SET status='CANCELLED' WHERE id=? AND status='PENDING'", rewardId);
            jdbc.update("UPDATE invite_relation SET status='REFUNDED' WHERE invitee_id=?", invitee);
            return;
        }

        var wallets = jdbc.queryForList("SELECT balance FROM wallet WHERE user_id=? FOR UPDATE", inviter);
        BigDecimal balance = wallets.isEmpty() ? BigDecimal.ZERO : (BigDecimal) wallets.get(0).get("balance");
        if (balance == null) balance = BigDecimal.ZERO;
        BigDecimal recovered = balance.min(rewardAmount).max(BigDecimal.ZERO).setScale(2);
        BigDecimal debt = rewardAmount.subtract(recovered).setScale(2);
        BigDecimal after = balance.subtract(recovered).max(BigDecimal.ZERO).setScale(2);
        if (recovered.signum() > 0) {
            jdbc.update("UPDATE wallet SET balance=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", after, inviter);
            jdbc.update("INSERT INTO `transaction`(type,user_type,user_id,amount,balance_before,balance_after,related_order_id,remark) " +
                            "VALUES('INVITE_REWARD_BACK','USER',?,?,?,?,?,?)",
                    inviter, recovered.negate(), balance, after, orderId, "邀请首单奖励退款冲正");
        }
        if (debt.signum() > 0) {
            try {
                jdbc.update("INSERT INTO invite_reward_debt(reward_id,inviter_id,amount,status) VALUES(?,?,?,'PENDING')",
                        rewardId, inviter, debt);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // The reward row is locked; a duplicate can only come from an earlier replay.
            }
        }
        jdbc.update("UPDATE invite_reward SET status='REVOKED' WHERE id=? AND status='PAID'", rewardId);
        jdbc.update("UPDATE invite_relation SET status='REFUNDED' WHERE invitee_id=?", invitee);
    }
}
