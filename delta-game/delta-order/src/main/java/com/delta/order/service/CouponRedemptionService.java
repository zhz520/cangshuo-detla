package com.delta.order.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class CouponRedemptionService {
    private final JdbcTemplate jdbc;

    public record CouponDiscount(Long couponId, String name, BigDecimal discount) {}

    public CouponDiscount quote(Long userId, Long couponId, BigDecimal orderAmount) {
        if (couponId == null) return new CouponDiscount(null, null, BigDecimal.ZERO);
        var rows = jdbc.queryForList("SELECT uc.id,uc.status,uc.expires_at,ct.name,ct.type,ct.coupon_value,ct.min_spend " +
                "FROM user_coupon uc JOIN coupon_template ct ON ct.id=uc.template_id " +
                "WHERE uc.id=? AND uc.user_id=? AND uc.status='AVAILABLE'", couponId, userId);
        if (rows.isEmpty()) throw new BusinessException(409, "优惠券不可用或已使用");
        var row = rows.get(0);
        if (row.get("expires_at") != null && ((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(java.time.Instant.now()))
            throw new BusinessException(409, "优惠券已过期");
        BigDecimal min = (BigDecimal) row.get("min_spend");
        if (min != null && orderAmount.compareTo(min) < 0) throw new BusinessException(409, "订单金额未达到优惠券门槛");
        BigDecimal value = (BigDecimal) row.get("coupon_value");
        BigDecimal discount = "DISCOUNT".equals(row.get("type"))
                ? orderAmount.multiply(BigDecimal.ONE.subtract(value))
                : value;
        discount = discount.setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
                .min(orderAmount.subtract(new BigDecimal("0.01")).max(BigDecimal.ZERO));
        return new CouponDiscount(couponId, String.valueOf(row.get("name")), discount);
    }

    @Transactional(rollbackFor = Exception.class)
    public void lock(Long userId, Long couponId, Long orderId) {
        int updated = jdbc.update("UPDATE user_coupon SET status='LOCKED',used_order_id=? WHERE id=? AND user_id=? AND status='AVAILABLE'",
                orderId, couponId, userId);
        if (updated != 1) throw new BusinessException(409, "优惠券正在被其他订单使用");
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(Long orderId) {
        jdbc.update("UPDATE user_coupon SET status='AVAILABLE',used_order_id=NULL WHERE used_order_id=? AND status='LOCKED'", orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void consume(Long orderId) {
        jdbc.update("UPDATE user_coupon SET status='USED',used_at=CURRENT_TIMESTAMP WHERE used_order_id=? AND status='LOCKED'", orderId);
    }
}
