package com.delta.admin.service;

import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.notification.NotificationTemplateSources;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves the full internal business context used by both official-account and SMS templates. */
@Service
@RequiredArgsConstructor
public class NotificationTemplateDataService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> MONEY_SOURCES = Set.of(
            "amount", "orderAmount", "baseAmount", "priceIncreaseAmount", "settleAmount",
            "refundAmount", "complaintRefundAmount", "withdrawAmount");
    private static final Set<String> WITHDRAW_EVENTS = Set.of(
            "WITHDRAW_APPLY", "WITHDRAW_COMPLETED", "WITHDRAW_REJECTED");

    private final JdbcTemplate jdbc;

    public Map<String, String> resolve(Map<String, Object> row) {
        Map<String, String> values = blankValues();
        String event = upper(row.get("event_type"));
        String eventTime = formatTime(row.get("created_at"), LocalDateTime.now());
        values.put("message", Objects.toString(row.get("message"), ""));
        values.put("status", SmsNoticeService.statusText(event));
        values.put("time", eventTime);
        values.put("eventType", event.isBlank() ? "-" : event);

        Long contextId = number(row.get("order_id"));
        boolean withdraw = WITHDRAW_EVENTS.contains(event);
        Map<String, Object> order = withdraw ? Map.of() : queryOne("""
                SELECT o.*,u.nickname customer_name,u.phone customer_phone,u.is_guest customer_is_guest,u.status customer_status,
                       p.nickname player_name,p.phone player_phone,p.level player_level_id,p.status player_status,
                       p.is_online player_online,p.order_count player_completed_orders,
                       pl.name player_level_display
                FROM `order` o
                LEFT JOIN `user` u ON u.id=o.user_id
                LEFT JOIN player p ON p.id=o.player_id
                LEFT JOIN player_level pl ON pl.id=p.level
                WHERE o.id=? LIMIT 1
                """, contextId);
        Map<String, Object> withdrawRow = withdraw ? queryOne("""
                SELECT w.*,p.nickname player_name,p.phone player_phone,p.level player_level_id,p.status player_status,
                       p.is_online player_online,p.order_count player_completed_orders,
                       pl.name player_level_display
                FROM withdraw w
                LEFT JOIN player p ON p.id=w.player_id
                LEFT JOIN player_level pl ON pl.id=p.level
                WHERE w.id=? LIMIT 1
                """, contextId) : Map.of();

        if (!order.isEmpty()) applyOrder(values, order);
        if (!withdrawRow.isEmpty()) applyWithdraw(values, withdrawRow);

        Long orderId = numberFrom(order, "id");
        if (refundEvent(event) && orderId != null) applyRefund(values, queryOne(
                "SELECT * FROM refund_request WHERE order_id=? ORDER BY id DESC LIMIT 1", orderId));
        if (complaintEvent(event) && orderId != null) applyComplaint(values, queryOne(
                "SELECT * FROM complaint WHERE order_id=? ORDER BY id DESC LIMIT 1", orderId));

        applyRecipient(values, row);
        return java.util.Collections.unmodifiableMap(values);
    }

    public static boolean moneySource(Object source) {
        return MONEY_SOURCES.contains(Objects.toString(source, ""));
    }

    private static Map<String, String> blankValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : NotificationTemplateSources.KEYS) values.put(key, MONEY_SOURCES.contains(key) ? "0.00" : "-");
        values.put("message", "");
        values.put("status", "状态更新");
        values.put("time", LocalDateTime.now().format(TIME));
        values.put("playerName", "未指派");
        return values;
    }

    private static void applyOrder(Map<String, String> values, Map<String, Object> o) {
        put(values, "customerId", o.get("user_id"));
        put(values, "customerName", o.get("customer_name"));
        values.put("customerPhone", text(o.get("customer_phone"), text(o.get("contact"), "-")));
        values.put("customerType", truthy(o.get("customer_is_guest")) ? "游客" : isPresent(o.get("user_id")) ? "客户" : "-");
        values.put("customerStatus", enabledText(o.get("customer_status")));
        put(values, "orderId", o.get("id"));
        put(values, "orderNo", o.get("order_no"));
        values.put("amount", money(o.get("amount")));
        values.put("orderAmount", money(o.get("amount")));
        values.put("baseAmount", money(o.get("base_amount")));
        values.put("priceIncreaseAmount", money(o.get("price_increase_amount")));
        put(values, "productId", o.get("product_id"));
        put(values, "productName", o.get("product_name"));
        put(values, "productSpec", o.get("spec_info"));
        values.put("orderStatus", orderStatus(o.get("status")));
        put(values, "gameAccount", o.get("game_account"));
        put(values, "orderContact", o.get("contact"));
        put(values, "orderRemark", o.get("remark"));
        put(values, "extraFields", o.get("extra_fields"));
        put(values, "requiredPlayerCount", o.get("required_player_count"));
        put(values, "requestedPlayerLevel", o.get("player_level_name"));
        put(values, "orderCreatedAt", time(o.get("created_at")));
        put(values, "payDeadline", time(o.get("pay_deadline")));
        put(values, "assignTime", time(o.get("assign_time")));
        put(values, "grabTime", time(o.get("accept_time")));
        put(values, "startTime", time(o.get("start_time")));
        put(values, "completeTime", time(o.get("complete_time")));
        put(values, "confirmTime", time(o.get("confirm_time")));
        put(values, "settleAmount", moneyOrDash(o.get("settle_amount")));
        put(values, "settleTime", time(o.get("settle_time")));
        put(values, "orderUpdatedAt", time(o.get("updated_at")));
        put(values, "playerId", o.get("player_id"));
        put(values, "playerName", o.get("player_name"));
        put(values, "playerPhone", o.get("player_phone"));
        String level = text(o.get("player_level_display"), text(o.get("player_level_id"), "-"));
        values.put("playerLevel", level);
        values.put("playerStatus", isPresent(o.get("player_id")) ? playerStatus(o.get("player_status")) : "-");
        values.put("playerOnline", !isPresent(o.get("player_id")) ? "-" : truthy(o.get("player_online")) ? "在线" : "离线");
        put(values, "playerCompletedOrders", o.get("player_completed_orders"));
    }

    private static void applyRefund(Map<String, String> values, Map<String, Object> r) {
        if (r.isEmpty()) return;
        put(values, "refundNo", r.get("refund_no"));
        put(values, "refundAmount", money(r.get("amount")));
        put(values, "refundReason", r.get("reason"));
        values.put("refundStatus", refundStatus(r.get("status")));
        put(values, "refundPaymentMethod", r.get("payment_method"));
        put(values, "refundSubmittedAt", time(r.get("submitted_at")));
        put(values, "refundCsReviewedAt", time(r.get("cs_reviewed_at")));
        put(values, "refundAdminDecidedAt", time(r.get("admin_decided_at")));
        put(values, "refundProcessingAt", time(r.get("processing_at")));
        put(values, "refundCompletedAt", time(r.get("completed_at")));
    }

    private static void applyComplaint(Map<String, String> values, Map<String, Object> c) {
        if (c.isEmpty()) return;
        values.put("complaintType", complaintType(c.get("type")));
        put(values, "complaintContent", c.get("content"));
        values.put("complaintExpectedResult", complaintResult(c.get("expected_result")));
        values.put("complaintStatus", complaintStatus(c.get("status")));
        values.put("complaintResult", complaintResult(c.get("result")));
        put(values, "complaintRefundAmount", moneyOrDash(c.get("refund_amount")));
        put(values, "complaintResultReason", c.get("result_reason"));
        values.put("complaintPlayerPenalty", penalty(c.get("player_penalty")));
        put(values, "complaintResolvedAt", time(c.get("resolved_at")));
        put(values, "complaintAppealReason", c.get("appeal_reason"));
        values.put("complaintAppealResult", complaintResult(c.get("appeal_result")));
    }

    private static void applyWithdraw(Map<String, String> values, Map<String, Object> w) {
        values.put("amount", money(w.get("amount")));
        put(values, "withdrawId", w.get("id"));
        put(values, "withdrawAmount", money(w.get("amount")));
        values.put("withdrawStatus", withdrawStatus(w.get("status")));
        put(values, "withdrawAccountType", w.get("account_type_snapshot"));
        put(values, "withdrawAccountName", w.get("account_name_snapshot"));
        put(values, "withdrawAccountNo", w.get("account_no_snapshot"));
        put(values, "withdrawMethod", w.get("pay_method"));
        put(values, "withdrawRejectReason", w.get("reject_reason"));
        put(values, "withdrawAppliedAt", time(w.get("created_at")));
        put(values, "withdrawProcessedAt", time(w.get("processed_at")));
        put(values, "withdrawProofUrl", w.get("proof_image"));
        put(values, "playerId", w.get("player_id"));
        put(values, "playerName", w.get("player_name"));
        put(values, "playerPhone", w.get("player_phone"));
        values.put("playerLevel", text(w.get("player_level_display"), text(w.get("player_level_id"), "-")));
        values.put("playerStatus", playerStatus(w.get("player_status")));
        values.put("playerOnline", truthy(w.get("player_online")) ? "在线" : "离线");
        put(values, "playerCompletedOrders", w.get("player_completed_orders"));
    }

    private void applyRecipient(Map<String, String> values, Map<String, Object> row) {
        String type = upper(row.get("user_type"));
        Long id = number(row.get("user_id"));
        values.put("recipientType", recipientType(type));
        put(values, "recipientId", id);
        Map<String, Object> recipient = switch (type) {
            case "USER", "GUEST" -> queryOne("SELECT nickname,phone FROM `user` WHERE id=? LIMIT 1", id);
            case "PLAYER" -> queryOne("SELECT nickname,phone FROM player WHERE id=? LIMIT 1", id);
            case "ADMIN", "CS" -> queryOne("SELECT COALESCE(NULLIF(nickname,''),username) name,phone FROM admin WHERE id=? LIMIT 1", id);
            default -> Map.of();
        };
        if (!recipient.isEmpty()) {
            put(values, "recipientName", recipient.containsKey("nickname") ? recipient.get("nickname") : recipient.get("name"));
            put(values, "recipientPhone", recipient.get("phone"));
        }
        if (("USER".equals(type) || "GUEST".equals(type)) && !hasValue(values.get("customerPhone"))) {
            put(values, "customerName", values.get("recipientName"));
            put(values, "customerPhone", values.get("recipientPhone"));
            values.put("customerType", "GUEST".equals(type) ? "游客" : "客户");
        }
        if ("PLAYER".equals(type) && !hasValue(values.get("playerPhone"))) {
            put(values, "playerId", id);
            put(values, "playerName", values.get("recipientName"));
            put(values, "playerPhone", values.get("recipientPhone"));
        }
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (DataAccessException ignored) {
            return Map.of();
        }
    }

    private static boolean refundEvent(String event) {
        return event.startsWith("REFUND_") || "ORDER_REFUNDED".equals(event) || "ORDER_CANCEL_REFUND".equals(event);
    }

    private static boolean complaintEvent(String event) {
        return "NEW_COMPLAINT".equals(event) || event.startsWith("COMPLAINT_");
    }

    private static void put(Map<String, String> values, String key, Object value) {
        if (isPresent(value)) values.put(key, String.valueOf(value).trim());
    }

    private static boolean isPresent(Object value) {
        return value != null && !String.valueOf(value).isBlank() && !"null".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean hasValue(Object value) {
        return isPresent(value) && !"-".equals(String.valueOf(value).trim());
    }

    private static String text(Object value, String fallback) {
        return isPresent(value) ? String.valueOf(value).trim() : fallback;
    }

    private static String upper(Object value) {
        return Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);

    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return isPresent(value) ? Long.valueOf(String.valueOf(value)) : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Long numberFrom(Map<String, Object> row, String key) {
        return number(row.get(key));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static String formatTime(Object value, LocalDateTime fallback) {
        if (value == null) return fallback.format(TIME);
        return time(value);
    }

    private static String time(Object value) {
        if (!isPresent(value)) return "-";
        if (value instanceof LocalDateTime time) return time.format(TIME);
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().format(TIME);
        if (value instanceof Date date) return new Timestamp(date.getTime()).toLocalDateTime().format(TIME);
        return String.valueOf(value).replace('T', ' ');
    }

    private static String money(Object value) {
        if (value == null) return "0.00";
        try { return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
        catch (NumberFormatException ignored) { return "0.00"; }
    }

    private static String moneyOrDash(Object value) {
        return value == null ? "-" : money(value);
    }

    private static String enabledText(Object value) {
        if (value == null) return "-";
        if (value instanceof Number number) return number.intValue() == 1 ? "正常" : "停用";
        return truthy(value) ? "正常" : "停用";
    }

    private static String orderStatus(Object value) {
        String code = upper(value);
        for (OrderStatusEnum status : OrderStatusEnum.values()) if (status.getCode().equals(code)) return status.getDesc();
        return code.isBlank() ? "-" : code;
    }

    private static String refundStatus(Object value) {
        return switch (upper(value)) {
            case "REQUESTED" -> "退款申请待处理";
            case "CS_REVIEWING" -> "客服核验中";
            case "CS_REVIEWED" -> "客服已初审";
            case "ADMIN_APPROVED" -> "管理员已批准";
            case "ADMIN_REJECTED" -> "管理员已驳回";
            case "PROCESSING" -> "退款处理中";
            case "REFUNDED" -> "退款成功";
            case "FAILED" -> "退款失败";
            default -> text(value, "-");
        };
    }

    private static String complaintType(Object value) {
        return switch (upper(value)) {
            case "SERVICE_QUALITY" -> "服务质量";
            case "TIMEOUT" -> "超时";
            case "ACCOUNT_ISSUE" -> "账号问题";
            case "FRAUD" -> "欺诈";
            case "OTHER" -> "其他";
            default -> text(value, "-");
        };
    }

    private static String complaintStatus(Object value) {
        return switch (upper(value)) {
            case "PENDING" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "RESOLVED" -> "已处理";
            case "APPEALED" -> "申诉中";
            default -> text(value, "-");
        };
    }

    private static String complaintResult(Object value) {
        return switch (upper(value)) {
            case "FULL_REFUND" -> "全额退款";
            case "PARTIAL_REFUND" -> "部分退款";
            case "REJECT" -> "驳回投诉";
            case "REDO" -> "重新服务";
            default -> text(value, "-");
        };
    }

    private static String penalty(Object value) {
        return switch (upper(value)) {
            case "NONE" -> "无";
            case "WARNING" -> "警告";
            case "DEDUCT_RATING" -> "扣减评分";
            case "FREEZE" -> "冻结账号";
            default -> text(value, "-");
        };
    }

    private static String withdrawStatus(Object value) {
        return switch (upper(value)) {
            case "PENDING" -> "待处理";
            case "COMPLETED" -> "已完成";
            case "REJECTED" -> "已驳回";
            default -> text(value, "-");
        };
    }

    private static String playerStatus(Object value) {
        return switch (upper(value)) {
            case "PENDING" -> "待审核";
            case "ACTIVE" -> "正常";
            case "REJECTED" -> "已驳回";
            case "FROZEN" -> "已冻结";
            default -> text(value, "-");
        };
    }

    private static String recipientType(String type) {
        return switch (type) {
            case "USER" -> "客户";
            case "GUEST" -> "游客";
            case "PLAYER" -> "打手";
            case "ADMIN" -> "管理员";
            case "CS" -> "客服";
            case "SYSTEM" -> "系统";
            default -> "-";
        };
    }
}
