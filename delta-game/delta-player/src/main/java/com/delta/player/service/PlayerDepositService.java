package com.delta.player.service;

import com.delta.common.mapper.CrossModuleMapper;
import com.delta.pay.service.PaymentService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Admin-only read model for player deposits and their payment receipts. */
@Service
@RequiredArgsConstructor
public class PlayerDepositService {
    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final CrossModuleMapper identities;
    private final SysConfigService config;

    public record Summary(Long playerId, Long userId, String mode, BigDecimal requiredAmount,
                          BigDecimal paidAmount, BigDecimal outstandingAmount, String status,
                          int paymentCount, String lastPaidAt) {}

    public record PaymentView(Long id, String paymentNo, BigDecimal amount, String status,
                              String payMethod, String paidAt, String createdAt,
                              String refundReason, String refundTime) {}

    public record PageResult(List<Map<String, Object>> records, long total, long pageNum, long pageSize) {}

    public Summary summary(Long playerId) {
        String mode = depositMode();
        Long userId = identities.selectUserIdByPlayerId(playerId);
        if (userId == null) {
            return new Summary(playerId, null, mode, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, "NO_PLAYER", 0, null);
        }
        BigDecimal required = money(payments.playerDepositRequiredAmount(userId));
        BigDecimal paid = money(payments.playerDepositPaidAmount(userId));
        BigDecimal outstanding = required.subtract(paid).max(BigDecimal.ZERO);
        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, MAX(paid_at) AS last_paid_at FROM payment " +
                        "WHERE user_id=? AND biz_type='PLAYER_DEPOSIT' AND status='PAID'", userId);
        int count = ((Number) stats.getOrDefault("cnt", 0)).intValue();
        String lastPaidAt = timestamp(stats.get("last_paid_at"));
        String status = required.signum() <= 0 ? "NONE"
                : paid.compareTo(required) >= 0 ? "PAID"
                : paid.signum() > 0 ? "PARTIAL" : "UNPAID";
        return new Summary(playerId, userId, mode, required, paid, outstanding, status, count, lastPaidAt);
    }

    public List<PaymentView> payments(Long playerId) {
        Long userId = identities.selectUserIdByPlayerId(playerId);
        if (userId == null) return List.of();
        return jdbc.query("SELECT id,payment_no,amount,status,pay_method,paid_at,created_at," +
                        "refund_reason,refund_time FROM payment WHERE user_id=? AND biz_type='PLAYER_DEPOSIT' " +
                        "ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, rowNum) -> paymentView(rs), userId);
    }

    public PageResult list(String keyword, String status, int pageNum, int pageSize) {
        int page = Math.max(1, pageNum);
        int size = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE pay.biz_type='PLAYER_DEPOSIT'");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND pay.status=?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (pay.payment_no LIKE ? OR p.nickname LIKE ? OR CAST(p.id AS CHAR) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = " FROM payment pay JOIN `user` u ON u.id=pay.user_id" +
                " LEFT JOIN player p ON p.openid=u.openid AND p.deleted=0" +
                " LEFT JOIN player_level pl ON pl.id=p.level";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> records = jdbc.query(
                "SELECT pay.id,pay.payment_no,pay.user_id,pay.amount,pay.status,pay.pay_method," +
                        "pay.paid_at,pay.created_at,pay.refund_reason,pay.refund_time," +
                        "p.id AS player_id,p.nickname AS player_name,p.level AS level_id,pl.name AS level_name" +
                        from + where + " ORDER BY pay.created_at DESC, pay.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> depositRow(rs), pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    private Map<String, Object> depositRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("paymentNo", rs.getString("payment_no"));
        row.put("userId", rs.getLong("user_id"));
        row.put("playerId", rs.getObject("player_id"));
        row.put("playerName", rs.getString("player_name"));
        row.put("levelId", rs.getObject("level_id"));
        row.put("levelName", rs.getString("level_name"));
        row.put("amount", money(rs.getBigDecimal("amount")));
        row.put("status", rs.getString("status"));
        row.put("payMethod", rs.getString("pay_method"));
        row.put("paidAt", timestamp(rs.getTimestamp("paid_at")));
        row.put("createdAt", timestamp(rs.getTimestamp("created_at")));
        row.put("refundReason", rs.getString("refund_reason"));
        row.put("refundTime", timestamp(rs.getTimestamp("refund_time")));
        return row;
    }

    private PaymentView paymentView(ResultSet rs) throws SQLException {
        return new PaymentView(rs.getLong("id"), rs.getString("payment_no"),
                money(rs.getBigDecimal("amount")), rs.getString("status"), rs.getString("pay_method"),
                timestamp(rs.getTimestamp("paid_at")), timestamp(rs.getTimestamp("created_at")),
                rs.getString("refund_reason"), timestamp(rs.getTimestamp("refund_time")));
    }

    private String depositMode() {
        String fallback = "true".equalsIgnoreCase(config.getConfigValue("player.deposit_required", "true"))
                ? "REQUIRED" : "NONE";
        String value = config.getConfigValue("player.deposit_mode", fallback);
        return (value == null ? fallback : value).trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String timestamp(Timestamp value) {
        if (value == null) return null;
        return value.toLocalDateTime().toString().replace('T', ' ');
    }

    private static String timestamp(Object value) {
        if (value instanceof Timestamp ts) return timestamp(ts);
        return value == null ? null : String.valueOf(value).replace('T', ' ');
    }
}
