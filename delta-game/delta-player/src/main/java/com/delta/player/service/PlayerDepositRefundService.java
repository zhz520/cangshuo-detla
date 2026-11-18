package com.delta.player.service;

import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.utils.IdGenerator;
import com.delta.pay.service.PaymentService;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Player-initiated deposit refunds with customer-service review and original-channel refund. */
@Service
@RequiredArgsConstructor
public class PlayerDepositRefundService {
    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final ApplicationEventPublisher events;

    public record PaymentOption(Long paymentId, String paymentNo, BigDecimal amount, String paidAt,
                                String refundStatus, @JsonSerialize(using = ToStringSerializer.class) Long refundId,
                                boolean eligible, String blockReason) {}
    public record RefundView(@JsonSerialize(using = ToStringSerializer.class) Long id, String refundNo, Long playerId, Long userId, Long paymentId,
                             BigDecimal amount, String reason, String status, Long reviewerId,
                             String reviewNote, String reviewedAt, String channelRefundNo,
                             String lastError, String createdAt, String completedAt) {}
    public record Context(Long playerId, BigDecimal requiredAmount, BigDecimal paidAmount,
                          BigDecimal outstandingAmount, List<PaymentOption> payments, List<RefundView> history) {}
    public record PageResult(List<Map<String, Object>> records, long total, long pageNum, long pageSize) {}

    public Context context(Long playerId) {
        Map<String, Object> player = playerRow(playerId);
        Long userId = number(player.get("user_id"));
        BigDecimal required = money(payments.playerDepositRequiredAmount(userId));
        BigDecimal paid = money(payments.playerDepositPaidAmount(userId));
        List<PaymentOption> options = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,payment_no,amount,paid_at,status FROM payment WHERE user_id=? " +
                        "AND biz_type='PLAYER_DEPOSIT' ORDER BY created_at DESC,id DESC", userId)) {
            Long paymentId = number(row.get("id"));
            Map<String, Object> active = activeRefund(paymentId);
            String block = eligibleBlockReason(playerId, userId, paymentId);
            options.add(new PaymentOption(paymentId, text(row.get("payment_no")), money(row.get("amount")),
                    timestamp(row.get("paid_at")), text(row.get("status")),
                    active == null ? null : number(active.get("id")), block == null, block));
        }
        return new Context(playerId, required, paid, required.subtract(paid).max(BigDecimal.ZERO),
                options, listForPlayer(playerId));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public RefundView submit(Long playerId, Long paymentId, String reason) {
        Map<String, Object> player = playerRow(playerId);
        Long userId = number(player.get("user_id"));
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.length() < 5 || cleanReason.length() > 500)
            throw new BusinessException(400, "退款原因须为5至500字");
        lockApplicant(playerId, userId);
        Map<String, Object> payment = lockPayment(paymentId);
        if (payment == null || !userId.equals(number(payment.get("user_id")))
                || !"PLAYER_DEPOSIT".equals(text(payment.get("biz_type"))))
            throw new BusinessException(404, "押金支付记录不存在");
        if (!"PAID".equals(text(payment.get("status"))))
            throw new BusinessException(409, "该押金当前不可申请退款");
        if (activeRefund(paymentId) != null) throw new BusinessException(409, "该押金已有退款申请在处理");
        String block = eligibleBlockReason(playerId, userId, paymentId);
        if (block != null) throw new BusinessException(409, block);

        long id = IdGenerator.nextId();
        jdbc.update("INSERT INTO player_deposit_refund(id,refund_no,player_id,user_id,payment_id,amount,reason," +
                        "status,active_payment_id) VALUES(?,?,?,?,?,?,?,'PENDING',?)",
                id, IdGenerator.nextRefundNo(), playerId, userId, paymentId,
                money(payment.get("amount")), cleanReason, paymentId);
        events.publishEvent(new BusinessEvent(this, "REFUND_REQUESTED", "PLAYER", playerId, null,
                "押金退款申请已提交，等待客服审核"));
        return getRequired(id);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void cancel(Long playerId, Long id) {
        Map<String, Object> row = lockRefund(id);
        if (!playerId.equals(number(row.get("player_id")))) throw new BusinessException(403, "无权操作该退款申请");
        if (!"PENDING".equals(text(row.get("status")))) throw new BusinessException(409, "当前退款申请不能取消");
        jdbc.update("UPDATE player_deposit_refund SET status='CANCELLED',active_payment_id=NULL," +
                "review_note='玩家取消',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", id);
        events.publishEvent(new BusinessEvent(this, "REFUND_FAILED", "PLAYER", playerId, null,
                "押金退款申请已取消"));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public RefundView review(Long id, Long reviewerId, boolean approve, String note) {
        Map<String, Object> row = approve ? lockForPaymentChange(id) : lockRefund(id);
        if (!"PENDING".equals(text(row.get("status")))) throw new BusinessException(409, "该退款申请已处理");
        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.length() > 500) throw new BusinessException(400, "审核说明不能超过500字");
        if (!approve) {
            jdbc.update("UPDATE player_deposit_refund SET status='REJECTED',active_payment_id=NULL," +
                            "reviewer_id=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE id=? AND status='PENDING'", reviewerId, cleanNote, id);
            events.publishEvent(new BusinessEvent(this, "REFUND_ADMIN_REJECTED", "PLAYER",
                    number(row.get("player_id")), null, "押金退款申请被驳回" +
                            (cleanNote.isBlank() ? "" : "：" + cleanNote)));
            return getRequired(id);
        }
        requireNoInFlightBusiness(number(row.get("player_id")));
        payments.refundPlayerDeposit(number(row.get("payment_id")), number(row.get("user_id")),
                money(row.get("amount")), "打手押金退款");
        String paymentStatus = jdbc.queryForObject("SELECT status FROM payment WHERE id=?", String.class,
                number(row.get("payment_id")));
        String next = "REFUNDED".equals(paymentStatus) ? "REFUNDED" : "REFUNDING";
        jdbc.update("UPDATE player_deposit_refund SET status=?,reviewer_id=?,review_note=?," +
                        "reviewed_at=CURRENT_TIMESTAMP,active_payment_id=?,completed_at=?," +
                        "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                next, reviewerId, cleanNote, "REFUNDED".equals(next) ? null : number(row.get("payment_id")),
                "REFUNDED".equals(next) ? new Timestamp(System.currentTimeMillis()) : null, id);
        events.publishEvent(new BusinessEvent(this, "REFUNDED".equals(next) ? "REFUND_SUCCEEDED" : "REFUND_PROCESSING",
                "PLAYER", number(row.get("player_id")), null,
                "REFUNDED".equals(next) ? "押金退款已原路退回" : "押金退款已提交支付渠道处理"));
        return getRequired(id);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public RefundView retry(Long id, Long reviewerId) {
        Map<String, Object> row = lockForPaymentChange(id);
        if (!"FAILED".equals(text(row.get("status"))) ||
                !PaymentService.DEPOSIT_REFUND_CLOSED.equals(row.get("last_error")))
            throw new BusinessException(409, "仅渠道已关闭的退款可重新发起，渠道异常须先在商户平台处理");
        requireNoInFlightBusiness(number(row.get("player_id")));
        // Move the existing request back to the active state before the payment outbox is written.
        jdbc.update("UPDATE player_deposit_refund SET status='REFUNDING',active_payment_id=?,reviewer_id=?," +
                        "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                number(row.get("payment_id")), reviewerId, id);
        payments.refundPlayerDeposit(number(row.get("payment_id")), number(row.get("user_id")),
                money(row.get("amount")), "打手押金退款重试");
        events.publishEvent(new BusinessEvent(this, "REFUND_PROCESSING", "PLAYER",
                number(row.get("player_id")), null, "押金退款重试已提交支付渠道"));
        return getRequired(id);
    }

    public List<RefundView> listForPlayer(Long playerId) {
        return jdbc.query("SELECT * FROM player_deposit_refund WHERE player_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
                (rs, n) -> refundView(rs), playerId);
    }

    public PageResult listForStaff(String keyword, String status, int pageNum, int pageSize) {
        int page = Math.max(1, pageNum);
        int size = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND r.status=?");
            args.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (r.refund_no LIKE ? OR p.nickname LIKE ? OR CAST(r.player_id AS CHAR) LIKE ?)");
            args.add(like); args.add(like); args.add(like);
        }
        String from = " FROM player_deposit_refund r JOIN player p ON p.id=r.player_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> records = jdbc.query(
                "SELECT r.*,p.nickname AS player_name,p.phone AS player_phone,pay.payment_no AS payment_no" +
                        " FROM player_deposit_refund r JOIN player p ON p.id=r.player_id" +
                        " LEFT JOIN payment pay ON pay.id=r.payment_id" + where +
                        " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> staffRow(rs), pageArgs.toArray());
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    private Map<String, Object> staffRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("refundNo", rs.getString("refund_no"));
        row.put("playerId", rs.getLong("player_id"));
        row.put("playerName", rs.getString("player_name"));
        row.put("playerPhone", rs.getString("player_phone"));
        row.put("paymentId", rs.getLong("payment_id"));
        row.put("paymentNo", rs.getString("payment_no"));
        row.put("amount", money(rs.getBigDecimal("amount")));
        row.put("reason", rs.getString("reason"));
        row.put("status", rs.getString("status"));
        row.put("reviewNote", rs.getString("review_note"));
        row.put("reviewedAt", timestamp(rs.getTimestamp("reviewed_at")));
        row.put("channelRefundNo", rs.getString("channel_refund_no"));
        row.put("lastError", rs.getString("last_error"));
        row.put("retryable", "FAILED".equals(rs.getString("status"))
                && PaymentService.DEPOSIT_REFUND_CLOSED.equals(rs.getString("last_error")));
        row.put("createdAt", timestamp(rs.getTimestamp("created_at")));
        row.put("completedAt", timestamp(rs.getTimestamp("completed_at")));
        return row;
    }

    private RefundView getRequired(Long id) {
        List<RefundView> rows = jdbc.query("SELECT * FROM player_deposit_refund WHERE id=?",
                (rs, n) -> refundView(rs), id);
        if (rows.isEmpty()) throw new BusinessException(404, "押金退款申请不存在");
        return rows.get(0);
    }

    private RefundView refundView(ResultSet rs) throws SQLException {
        return new RefundView(rs.getLong("id"), rs.getString("refund_no"), rs.getLong("player_id"),
                rs.getLong("user_id"), rs.getLong("payment_id"), money(rs.getBigDecimal("amount")),
                rs.getString("reason"), rs.getString("status"), nullableLong(rs, "reviewer_id"),
                rs.getString("review_note"), timestamp(rs.getTimestamp("reviewed_at")),
                rs.getString("channel_refund_no"), rs.getString("last_error"),
                timestamp(rs.getTimestamp("created_at")), timestamp(rs.getTimestamp("completed_at")));
    }

    private Map<String, Object> playerRow(Long playerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id AS player_id,u.id AS user_id FROM player p JOIN `user` u ON u.openid=p.openid " +
                        "AND u.deleted=0 WHERE p.id=? AND p.deleted=0 LIMIT 1", playerId);
        if (rows.isEmpty()) throw new BusinessException(404, "打手不存在");
        return rows.get(0);
    }

    private void lockApplicant(Long playerId, Long userId) {
        // Match payment callbacks: user first, then player, wallet, payment and request.
        if (jdbc.queryForList("SELECT id FROM `user` WHERE id=? AND deleted=0 FOR UPDATE", userId).isEmpty()
                || jdbc.queryForList("SELECT id FROM player WHERE id=? AND deleted=0 FOR UPDATE", playerId).isEmpty()
                || !userId.equals(number(playerRow(playerId).get("user_id"))))
            throw new BusinessException(409, "打手与支付账号关联已变更，请重新核对");
        jdbc.queryForList("SELECT player_id FROM player_wallet WHERE player_id=? FOR UPDATE", playerId);
    }

    private Map<String, Object> lockForPaymentChange(Long id) {
        RefundView view = getRequired(id);
        lockApplicant(view.playerId(), view.userId());
        lockPayment(view.paymentId());
        return lockRefund(id);
    }

    private Map<String, Object> lockPayment(Long paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,user_id,biz_type,amount,status,payment_no FROM payment WHERE id=? FOR UPDATE", paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> lockRefund(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM player_deposit_refund WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BusinessException(404, "押金退款申请不存在");
        return rows.get(0);
    }

    private Map<String, Object> activeRefund(Long paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status FROM player_deposit_refund WHERE payment_id=? " +
                        "AND status IN ('PENDING','REFUNDING') LIMIT 1", paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String eligibleBlockReason(Long playerId, Long userId, Long paymentId) {
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT status,amount FROM payment WHERE id=? AND user_id=? AND biz_type='PLAYER_DEPOSIT'",
                paymentId, userId);
        if (!"PAID".equals(text(payment.get("status")))) return "该押金当前不可退款";
        if (activeRefund(paymentId) != null) return "该押金已有退款申请在处理";
        return businessBlockReason(playerId);
    }

    private void requireNoInFlightBusiness(Long playerId) {
        String block = businessBlockReason(playerId);
        if (block != null) throw new BusinessException(409, block);
    }

    private String businessBlockReason(Long playerId) {
        var wallets = jdbc.queryForList("SELECT frozen_amount FROM player_wallet WHERE player_id=?", playerId);
        if (!wallets.isEmpty() && money(wallets.get(0).get("frozen_amount")).signum() > 0)
            return "存在冻结中的提现，暂不能退押金";
        long withdrawals = count("SELECT COUNT(*) FROM withdraw WHERE player_id=? AND status IN ('PENDING','PROCESSING')", playerId);
        if (withdrawals > 0) return "存在处理中提现，暂不能退押金";
        long activeOrders = count("SELECT COUNT(*) FROM `order` o WHERE o.status IN " +
                "('ASSIGNED','ACCEPTED','IN_PROGRESS','WAITING_TEAMMATE','COMPLETED','DISPUTED','REFUNDING') " +
                "AND (o.player_id=? OR EXISTS (SELECT 1 FROM order_player op WHERE op.order_id=o.id " +
                "AND op.player_id=? AND op.status IN ('INVITED','ACCEPTED')))", playerId, playerId);
        if (activeOrders > 0) return "存在进行中或待结算订单，暂不能退押金";
        if (count("SELECT COUNT(*) FROM player_income_settlement WHERE player_id=? AND status IN ('PENDING','PROCESSING')", playerId) > 0)
            return "存在待结算收益，暂不能退押金";
        long debt = count("SELECT COUNT(*) FROM player_refund_recovery WHERE player_id=? AND remaining>0", playerId);
        if (debt > 0) return "存在待追回款，暂不能退押金";
        return null;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal money(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static String timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toString().replace('T', ' ');
        return String.valueOf(value).replace('T', ' ');
    }
}
