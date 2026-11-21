package com.delta.marketing.listener;

import com.delta.common.event.BusinessEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class CouponRefundListener {
    private final JdbcTemplate jdbc;

    @EventListener
    public void onOrderRefunded(BusinessEvent event) {
        if (!"ORDER_REFUNDED".equals(event.getEventType()) || event.getOrderId() == null) return;
        Long orderId = event.getOrderId();
        var rows = jdbc.queryForList("SELECT o.amount,COALESCE(SUM(p.refund_amount),0) refunded FROM `order` o " +
                "LEFT JOIN payment p ON p.order_id=o.id WHERE o.id=? GROUP BY o.id,o.amount", orderId);
        if (rows.isEmpty()) return;
        BigDecimal amount = (BigDecimal) rows.get(0).get("amount");
        BigDecimal refunded = (BigDecimal) rows.get(0).get("refunded");
        if (amount != null && refunded != null && refunded.compareTo(amount) >= 0)
            jdbc.update("UPDATE user_coupon SET status=CASE WHEN expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'AVAILABLE' END," +
                    "used_order_id=NULL,used_at=NULL WHERE used_order_id=? AND status='USED'", orderId);
    }
}
