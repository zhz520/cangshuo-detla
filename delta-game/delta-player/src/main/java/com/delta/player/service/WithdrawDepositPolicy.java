package com.delta.player.service;

import com.delta.pay.service.PaymentService;
import com.delta.system.service.SysConfigService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Locale;

/** The withdrawal page and the workbench use the same outstanding deposit rule. */
public final class WithdrawDepositPolicy {
    private WithdrawDepositPolicy() {}

    public record Status(String mode, BigDecimal requiredAmount, BigDecimal paidAmount,
                         BigDecimal outstandingAmount, boolean needsPayment) {}

    public static Status evaluate(Long playerId, JdbcTemplate jdbc, SysConfigService config, PaymentService payments) {
        String fallback = "true".equalsIgnoreCase(config.getConfigValue("player.deposit_required", "true")) ? "REQUIRED" : "NONE";
        String value = config.getConfigValue("player.deposit_mode", fallback);
        String mode = (value == null ? fallback : value).trim().toUpperCase(Locale.ROOT);
        var users = jdbc.queryForList("""
                SELECT u.id AS user_id FROM `user` u JOIN player p ON p.openid=u.openid AND p.deleted=0
                WHERE p.id=? AND u.deleted=0 LIMIT 1
                """, playerId);
        if (users.isEmpty()) return new Status(mode, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        Long userId = ((Number) users.get(0).get("user_id")).longValue();
        BigDecimal required = "BEFORE_WITHDRAW".equals(mode) ? payments.playerDepositRequiredAmount(userId) : BigDecimal.ZERO;
        BigDecimal paid = java.util.Objects.requireNonNullElse(payments.playerDepositPaidAmount(userId), BigDecimal.ZERO);
        required = java.util.Objects.requireNonNullElse(required, BigDecimal.ZERO);
        BigDecimal outstanding = required.subtract(paid).max(BigDecimal.ZERO);
        return new Status(mode, required, paid, outstanding, outstanding.signum() > 0);
    }
}
