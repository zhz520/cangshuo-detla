package com.delta.admin.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders all dynamic values as text and never accepts SMTP credentials. */
@Component
public class EmailNotificationTemplate {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record EventDescriptor(String id, String name, String description) {}
    public record RenderedEmail(String subject, String plainText, String htmlText) {}

    private static final List<EventDescriptor> EVENTS = List.of(
            event("ORDER_PAID", "订单已支付", "新订单支付成功后通知运营"),
            event("ORDER_ASSIGNED", "订单已指派", "订单派给打手后通知运营"),
            event("ORDER_ACCEPTED", "订单已接单", "打手接单后通知运营"),
            event("ORDER_COMPLETED", "订单已完成", "订单完成或确认后通知运营"),
            event("ORDER_CANCELLED", "订单已取消", "订单取消后通知运营"),
            event("ORDER_REFUNDED", "订单已退款", "订单退款完成后通知运营"),
            event("NEW_COMPLAINT", "新售后投诉", "客户提交投诉后通知运营"),
            event("COMPLAINT_RESOLVED", "投诉已处理", "售后投诉处理完成后通知运营"),
            event("WITHDRAW_APPLY", "提现申请", "打手提交提现申请时通知运营"),
            event("WITHDRAW_COMPLETED", "提现已完成", "提现处理成功后通知运营"),
            event("WITHDRAW_REJECTED", "提现被驳回", "提现申请驳回后通知运营"),
            event("PLAYER_APPROVED", "打手审核通过", "打手入驻审核通过后通知运营"),
            event("PLAYER_REJECTED", "打手审核驳回", "打手入驻审核驳回后通知运营"),
            event("PLAYER_FROZEN", "打手已冻结", "打手账号被冻结时通知运营"),
            event("MATERIAL_REVIEWED", "资料审核完成", "打手资料审核完成后通知运营"),
            event("NEW_REVIEW", "新评价", "客户提交新评价后通知运营"),
            event("INCOME_SETTLED", "收益已结算", "收益结算完成后通知运营")
    );
    private static final Map<String, EventDescriptor> EVENTS_BY_ID;
    static {
        Map<String, EventDescriptor> values = new LinkedHashMap<>();
        for (EventDescriptor event : EVENTS) values.put(event.id(), event);
        EVENTS_BY_ID = Map.copyOf(values);
    }

    private final Clock clock;

    public EmailNotificationTemplate() {
        this(Clock.systemDefaultZone());
    }

    EmailNotificationTemplate(Clock clock) {
        this.clock = clock;
    }

    public List<EventDescriptor> availableEvents() {
        return EVENTS;
    }

    public boolean supports(String eventType) {
        return eventType != null && EVENTS_BY_ID.containsKey(eventType.trim().toUpperCase());
    }

    public String displayName(String eventType) {
        EventDescriptor descriptor = EVENTS_BY_ID.get(eventType == null ? "" : eventType.trim().toUpperCase());
        return descriptor == null ? "业务通知" : descriptor.name();
    }

    public RenderedEmail render(String eventType, String message, Long orderId, String orderNo, String brand) {
        String name = displayName(eventType);
        String reference = orderReference(orderId, orderNo);
        String subject = "[运营通知] " + name + (reference.isBlank() ? "" : " - " + reference);
        String content = message == null || message.isBlank() ? "系统检测到一项业务状态变更。" : message.trim();
        if (content.length() > 1000) content = content.substring(0, 1000);
        String time = LocalDateTime.now(clock).format(TIME);
        String plain = name + "\n\n" + content + "\n\n" + "事件：" + eventType + "\n"
                + (reference.isBlank() ? "" : reference + "\n") + "时间：" + time + "\n";
        String accent = eventType.startsWith("WITHDRAW") || "INCOME_SETTLED".equals(eventType) ? "#157a55"
                : eventType.startsWith("COMPLAINT") ? "#b43b48"
                : eventType.startsWith("PLAYER") || eventType.startsWith("MATERIAL") ? "#7252a3" : "#2563eb";
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f3f5f8;color:#202328;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent\">" + escape(subject) + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f3f5f8\"><tr><td align=\"center\" style=\"padding:32px 14px\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"max-width:620px;background:#fff;border:1px solid #e4e8ee;border-radius:18px;overflow:hidden;box-shadow:0 12px 34px rgba(31,42,58,.07)\">"
                + "<tr><td style=\"padding:24px 28px;background:#202328;color:#fff\"><table role=\"presentation\" width=\"100%\"><tr><td style=\"width:42px\"><div style=\"width:38px;height:38px;line-height:38px;border-radius:11px;background:#fff;color:#202328;text-align:center;font-size:19px;font-weight:800\">C</div></td>"
                + "<td style=\"padding-left:13px\"><div style=\"font-size:16px;font-weight:700\">" + escape(brand == null || brand.isBlank() ? "运营通知" : brand) + "</div><div style=\"margin-top:3px;color:#aeb6c2;font-size:11px;letter-spacing:1.4px\">AUTOMATED NOTICE</div></td></tr></table></td></tr>"
                + "<tr><td style=\"padding:30px 28px 8px\"><span style=\"display:inline-block;padding:5px 10px;border-radius:999px;background:" + accent + "18;color:" + accent + ";font-size:12px;font-weight:700\">" + escape(name) + "</span>"
                + "<h1 style=\"margin:16px 0 20px;color:#17191c;font-size:24px;line-height:1.4;font-weight:750\">" + escape(name) + "</h1>"
                + "<div style=\"padding:20px 22px;border:1px solid #e8ebf0;border-radius:12px;background:#fafbfc\"><p style=\"margin:0 0 14px;color:#3f4a59;font-size:15px;line-height:1.8\">" + escape(content).replace("\n", "<br>") + "</p>"
                + "<table role=\"presentation\" style=\"width:100%;border-collapse:collapse;font-size:13px\">" + row("事件", eventType)
                + (reference.isBlank() ? "" : row("订单", reference)) + row("时间", time) + "</table></div></td></tr>"
                + "<tr><td style=\"padding:18px 28px 26px\"><div style=\"height:1px;background:#eceff3;margin-bottom:16px\"></div><p style=\"margin:0;color:#8a94a3;font-size:12px;line-height:1.7\">这是由业务系统自动发送的邮件通知，请勿直接回复。<br>如需处理，请登录管理后台查看相关订单与记录。</p></td></tr>"
                + "</table><p style=\"margin:14px 0 0;color:#9ba4b1;font-size:11px\">" + escape(brand == null || brand.isBlank() ? "运营通知" : brand) + " · 系统通知</p></td></tr></table></body></html>";
        return new RenderedEmail(subject, plain, html);
    }

    public RenderedEmail renderTest(String brand) {
        String time = LocalDateTime.now(clock).format(TIME);
        return new RenderedEmail("[运营通知] SMTP 测试邮件",
                "这是一封邮件通知配置测试。\n\n时间：" + time + "\n如果收到此邮件，表示 SMTP 配置可用。\n",
                "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                        + "<body style=\"margin:0;padding:32px 14px;background:#f3f5f8;color:#202328;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif\">"
                        + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tr><td align=\"center\"><table role=\"presentation\" width=\"100%\" style=\"max-width:620px;background:#fff;border:1px solid #e4e8ee;border-radius:18px;overflow:hidden\">"
                        + "<tr><td style=\"padding:24px 28px;background:#202328;color:#fff\"><div style=\"width:38px;height:38px;line-height:38px;border-radius:11px;background:#fff;color:#202328;text-align:center;font-size:19px;font-weight:800\">C</div><div style=\"margin-top:12px;font-size:16px;font-weight:700\">" + escape(brand == null || brand.isBlank() ? "运营通知" : brand) + "</div><div style=\"margin-top:3px;color:#aeb6c2;font-size:11px;letter-spacing:1.4px\">AUTOMATED NOTICE</div></td></tr>"
                        + "<tr><td style=\"padding:30px 28px\"><span style=\"display:inline-block;padding:5px 10px;border-radius:999px;background:#eff6ff;color:#2563eb;font-size:12px;font-weight:700\">邮箱配置测试</span><h1 style=\"margin:16px 0 20px;font-size:24px\">SMTP 测试邮件</h1><div style=\"padding:20px 22px;border:1px solid #e8ebf0;border-radius:12px;background:#fafbfc;line-height:1.8\"><p style=\"margin:0 0 12px\">这是一封邮件通知配置测试。如果收到此邮件，表示 SMTP 配置可用。</p><p style=\"margin:0;color:#6b7280;font-size:13px\">时间：" + escape(time) + "</p></div></td></tr>"
                        + "<tr><td style=\"padding:0 28px 26px;color:#8a94a3;font-size:12px;line-height:1.7\">这是由业务系统自动发送的邮件通知，请勿直接回复。</td></tr></table></td></tr></table></body></html>");
    }

    private static String orderReference(Long orderId, String orderNo) {
        if (orderNo != null && !orderNo.isBlank()) return "订单号 " + orderNo.trim();
        return orderId == null ? "" : "订单 #" + orderId;
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"width:90px;padding:9px 0;color:#6b7280;border-top:1px solid #eef1f4\">" + escape(label)
                + "</td><td style=\"padding:9px 0;border-top:1px solid #eef1f4\">" + escape(value) + "</td></tr>";
    }

    private static EventDescriptor event(String id, String name, String description) {
        return new EventDescriptor(id, name, description);
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
