package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.delta.platform.PlatformService;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class EmailNotificationService {
    public static final String FEATURE_ID = "email_notifications";
    public static final String PREFIX = "email.notifications.";
    public static final String ENABLED_KEY = PREFIX + "enabled";
    public static final String HOST_KEY = PREFIX + "host";
    public static final String PORT_KEY = PREFIX + "port";
    public static final String SECURITY_KEY = PREFIX + "security";
    public static final String USERNAME_KEY = PREFIX + "username";
    public static final String PASSWORD_KEY = PREFIX + "password";
    public static final String FROM_ADDRESS_KEY = PREFIX + "from_address";
    public static final String SENDER_NAME_KEY = PREFIX + "sender_name";
    public static final String RECIPIENTS_KEY = PREFIX + "recipients";
    public static final String EVENTS_KEY = PREFIX + "events";

    private static final int MAX_RECIPIENTS = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final int DELIVERY_BATCH = 20;
    private static final Duration MAX_AGE = Duration.ofDays(1);
    private static final String MASK = "********";

    private final JdbcTemplate jdbc;
    private final SysConfigService configs;
    private final PlatformService platform;
    private final EmailNotificationTemplate templates;
    private final Clock clock;
    @Autowired(required=false) private TenantRegistry tenantRegistry;

    @Autowired
    public EmailNotificationService(JdbcTemplate jdbc, SysConfigService configs,
                                    PlatformService platform, EmailNotificationTemplate templates) {
        this(jdbc, configs, platform, templates, Clock.systemUTC());
    }

    EmailNotificationService(JdbcTemplate jdbc, SysConfigService configs, PlatformService platform,
                             EmailNotificationTemplate templates, Clock clock) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.platform = platform;
        this.templates = templates;
        this.clock = clock;
    }

    public record Settings(boolean enabled, String host, int port, String security, String username,
                           String fromAddress, String senderName, List<String> recipients, List<String> events,
                           String password, boolean passwordConfigured, boolean licensed,
                           List<EmailNotificationTemplate.EventDescriptor> availableEvents) {}

    public record SettingsRequest(Boolean enabled, String host, Integer port, String security, String username,
                                  String fromAddress, String senderName, List<String> recipients,
                                  List<String> events, String password) {}

    public record TestRequest(Object recipients) {}

    public Settings settings() {
        SettingsData data = readSettings();
        return new Settings(data.enabled(), data.host(), data.port(), data.security(), data.username(),
                data.fromAddress(), data.senderName(), data.recipients(), data.events(), "",
                !data.password().isBlank(), platform.featureEnabled(FEATURE_ID), templates.availableEvents());
    }

    @Transactional(rollbackFor = Exception.class)
    public Settings save(SettingsRequest request) {
        if (request == null) throw new BusinessException(400, "邮件通知配置不能为空");
        String currentPassword = configs.getConfigValue(PASSWORD_KEY, "");
        String suppliedPassword = cleanText(request.password());
        if (MASK.equals(suppliedPassword)) suppliedPassword = "";
        String password = suppliedPassword.isBlank() ? Objects.toString(currentPassword, "") : suppliedPassword;

        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String host = cleanText(request.host());
        int port = request.port() == null ? 465 : request.port();
        if (port < 1 || port > 65535) throw new BusinessException(400, "SMTP 端口须在 1 至 65535 之间");
        String security = cleanText(request.security()).toUpperCase(Locale.ROOT);
        if (security.isBlank()) security = "TLS";
        if (!Set.of("TLS", "STARTTLS").contains(security)) throw new BusinessException(400, "连接加密须为 TLS 或 STARTTLS");
        String username = cleanText(request.username());
        String fromAddress = normalizeEmail(request.fromAddress(), "发件邮箱");
        String senderName = cleanText(request.senderName());
        if (senderName.isBlank()) senderName = "运营通知";
        if (senderName.length() > 60) throw new BusinessException(400, "发件人名称最多 60 个字符");
        List<String> recipients = normalizeEmails(request.recipients(), "收件邮箱", false);
        List<String> events = normalizeEvents(request.events());

        if (host.length() > 253 || (!host.isBlank() && !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")))
            throw new BusinessException(400, "SMTP 服务器地址格式不正确");
        if (enabled) {
            if (host.isBlank()) throw new BusinessException(400, "启用邮件通知前请填写 SMTP 服务器");
            if (username.isBlank()) throw new BusinessException(400, "启用邮件通知前请填写邮箱账号");
            if (fromAddress.isBlank()) throw new BusinessException(400, "启用邮件通知前请填写发件邮箱");
            if (password.isBlank()) throw new BusinessException(400, "启用邮件通知前请填写邮箱密码或授权码");
            if (recipients.isEmpty()) throw new BusinessException(400, "启用邮件通知前至少添加一个收件邮箱");
            if (events.isEmpty()) throw new BusinessException(400, "启用邮件通知前至少选择一个通知事件");
        }

        writeConfig(ENABLED_KEY, Boolean.toString(enabled), "boolean", "启用邮件通知", "启用后仅发送已勾选的业务事件");
        writeConfig(HOST_KEY, host, "text", "SMTP 服务器", "客户自有 SMTP 服务器地址");
        writeConfig(PORT_KEY, Integer.toString(port), "number", "SMTP 端口", "通常 TLS 使用 465，STARTTLS 使用 587");
        writeConfig(SECURITY_KEY, security, "text", "SMTP 加密方式", "TLS 或 STARTTLS");
        writeConfig(USERNAME_KEY, username, "text", "SMTP 邮箱账号", "用于 SMTP 身份认证的邮箱账号");
        writeConfig(PASSWORD_KEY, password, "text", "SMTP 密码", "仅由邮件通知专用接口维护，保存后不回显");
        writeConfig(FROM_ADDRESS_KEY, fromAddress, "text", "发件邮箱", "运营通知的 From 地址");
        writeConfig(SENDER_NAME_KEY, senderName, "text", "发件人名称", "收件人看到的发件人名称");
        writeConfig(RECIPIENTS_KEY, JSON.toJSONString(recipients), "json", "运营收件邮箱", "最多 20 个邮箱，自动去重");
        writeConfig(EVENTS_KEY, JSON.toJSONString(events), "json", "邮件通知事件", "只排队已勾选的事件");
        return settings();
    }

    @Transactional(rollbackFor = Exception.class)
    public int test(TestRequest request) {
        SettingsData data = readSettings();
        if (!data.enabled()) throw new BusinessException(409, "请先启用邮件通知");
        validateDeliveryConfiguration(data);
        List<String> requested = request == null ? List.of() : normalizeRequestedRecipients(request.recipients());
        List<String> recipients = requested.isEmpty() ? data.recipients() : requested;
        if (recipients.isEmpty()) throw new BusinessException(400, "请至少填写一个测试收件邮箱");
        EmailNotificationTemplate.RenderedEmail content = templates.renderTest(data.senderName());
        String hash = sha256("TEST\n" + UUID.randomUUID());
        for (String recipient : recipients) enqueue(hash, "TEST", recipient, content);
        return recipients.size();
    }

    public List<Map<String, Object>> records(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT id,event_type,recipient,subject,status,attempts,last_error,next_at,created_at,sent_at " +
                        "FROM email_notice_queue ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("recipient", rs.getString("recipient"));
                    row.put("subject", rs.getString("subject"));
                    row.put("status", rs.getString("status"));
                    row.put("attempts", rs.getInt("attempts"));
                    row.put("last_error", rs.getString("last_error"));
                    row.put("next_at", rs.getTimestamp("next_at"));
                    row.put("created_at", rs.getTimestamp("created_at"));
                    row.put("sent_at", rs.getTimestamp("sent_at"));
                    return row;
                }, safeLimit);
    }

    public void retry(String id) {
        if (id == null || id.isBlank() || id.length() > 36) throw new BusinessException(400, "发送记录编号无效");
        int updated = jdbc.update("UPDATE email_notice_queue SET status='PENDING',attempts=0,last_error=NULL," +
                "next_at=CURRENT_TIMESTAMP,created_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('FAILED','RETRY')", id);
        if (updated > 0) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM email_notice_queue WHERE id=?", Long.class, id);
        if (count == null || count == 0) throw new BusinessException(404, "发送记录不存在");
        throw new BusinessException(409, "该发送记录当前不能重试");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void onBusinessEvent(BusinessEvent event) {
        if (event == null || !platform.featureEnabled(FEATURE_ID)) return;
        String eventType = Objects.toString(event.getEventType(), "").trim().toUpperCase(Locale.ROOT);
        if (!templates.supports(eventType)) return;
        SettingsData data = readSettings();
        if (!data.enabled() || !data.events().contains(eventType) || data.recipients().isEmpty()) return;
        String orderNo = findOrderNo(event.getOrderId());
        EmailNotificationTemplate.RenderedEmail content = templates.render(
                eventType, event.getMessage(), event.getOrderId(), orderNo, data.senderName());
        String hash = eventHash(eventType, event.getUserType(), event.getUserId(), event.getOrderId(), event.getMessage());
        for (String recipient : data.recipients()) enqueue(hash, eventType, recipient, content);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void deliver() {
        TenantScheduler.run("email-notice-delivery", tenantRegistry,this::deliverForTenant);
    }

    private synchronized void deliverForTenant() {
        if (!platform.featureEnabled(FEATURE_ID)) return;
        SettingsData data = readSettings();
        if (!data.enabled() || !isDeliveryConfigured(data)) return;

        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE email_notice_queue SET status='EXPIRED',last_error=NULL " +
                "WHERE status IN ('PENDING','RETRY') AND created_at<?", now.minus(MAX_AGE));
        jdbc.update("UPDATE email_notice_queue SET status='RETRY',next_at=CURRENT_TIMESTAMP " +
                "WHERE status='SENDING' AND next_at<CURRENT_TIMESTAMP");
        List<Map<String, Object>> batch = jdbc.queryForList(
                "SELECT id,event_type,event_hash,recipient,subject,plain_body,html_body,attempts,created_at " +
                        "FROM email_notice_queue WHERE status IN ('PENDING','RETRY') AND next_at<=CURRENT_TIMESTAMP " +
                        "ORDER BY created_at,id LIMIT ?", DELIVERY_BATCH);
        JavaMailSender mailSender = createMailSender(data);
        for (Map<String, Object> row : batch) {
            String id = Objects.toString(row.get("id"), "");
            int nextAttempt = ((Number) row.get("attempts")).intValue() + 1;
            int claimed = jdbc.update("UPDATE email_notice_queue SET status='SENDING',attempts=?,next_at=? " +
                            "WHERE id=? AND status IN ('PENDING','RETRY')",
                    nextAttempt, now.plusMinutes(5), id);
            if (claimed == 0) continue;
            try {
                send(mailSender, data, row);
                jdbc.update("UPDATE email_notice_queue SET status='SENT',last_error=NULL,sent_at=CURRENT_TIMESTAMP WHERE id=?", id);
            } catch (Exception error) {
                LocalDateTime createdAt = asDateTime(row.get("created_at"));
                boolean expired = createdAt != null && !now.isBefore(createdAt.plus(MAX_AGE));
                boolean failed = nextAttempt >= MAX_ATTEMPTS;
                String status = expired ? "EXPIRED" : failed ? "FAILED" : "RETRY";
                LocalDateTime nextAt = expired ? now : nextAttemptTime(now, createdAt, nextAttempt);
                jdbc.update("UPDATE email_notice_queue SET status=?,last_error=?,next_at=? WHERE id=?",
                        status, failureCode(error), nextAt, id);
            }
        }
    }

    public void ensureDefaults() {
        ensureConfig(ENABLED_KEY, "false", "boolean", "启用邮件通知", "邮件通知总开关");
        ensureConfig(HOST_KEY, "", "text", "SMTP 服务器", "客户自有 SMTP 服务器地址");
        ensureConfig(PORT_KEY, "465", "number", "SMTP 端口", "通常 TLS 使用 465，STARTTLS 使用 587");
        ensureConfig(SECURITY_KEY, "TLS", "text", "SMTP 加密方式", "TLS 或 STARTTLS");
        ensureConfig(USERNAME_KEY, "", "text", "SMTP 邮箱账号", "用于 SMTP 身份认证的邮箱账号");
        ensureConfig(PASSWORD_KEY, "", "text", "SMTP 密码", "仅由邮件通知专用接口维护，保存后不回显");
        ensureConfig(FROM_ADDRESS_KEY, "", "text", "发件邮箱", "运营通知的 From 地址");
        ensureConfig(SENDER_NAME_KEY, "运营通知", "text", "发件人名称", "收件人看到的发件人名称");
        ensureConfig(RECIPIENTS_KEY, "[]", "json", "运营收件邮箱", "最多 20 个邮箱，自动去重");
        ensureConfig(EVENTS_KEY, JSON.toJSONString(defaultEvents()), "json", "邮件通知事件", "只排队已勾选的事件");
    }

    protected JavaMailSender createMailSender(SettingsData settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        if ("TLS".equals(settings.security())) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.starttls.enable", "false");
        } else {
            properties.put("mail.smtp.ssl.enable", "false");
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        sender.setJavaMailProperties(new java.util.Properties());
        properties.forEach((key, value) -> sender.getJavaMailProperties().setProperty(key, value));
        return sender;
    }

    private void send(JavaMailSender sender, SettingsData settings, Map<String, Object> row) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(settings.fromAddress(), settings.senderName());
        helper.setTo(Objects.toString(row.get("recipient")));
        helper.setSubject(Objects.toString(row.get("subject")));
        helper.setText(Objects.toString(row.get("plain_body")), Objects.toString(row.get("html_body")));
        sender.send(message);
    }

    private void enqueue(String hash, String eventType, String recipient,
                         EmailNotificationTemplate.RenderedEmail content) {
        try {
            jdbc.update("INSERT INTO email_notice_queue(id,event_type,event_hash,recipient,subject,plain_body,html_body,status) " +
                            "VALUES (?,?,?,?,?,?,?,'PENDING')",
                    UUID.randomUUID().toString(), eventType, hash, recipient, content.subject(),
                    content.plainText(), content.htmlText());
        } catch (DuplicateKeyException ignored) {
            // The unique (event_hash,recipient) key makes redelivery idempotent.
        }
    }

    private SettingsData readSettings() {
        boolean enabled = Boolean.parseBoolean(configs.getConfigValue(ENABLED_KEY, "false"));
        String host = Objects.toString(configs.getConfigValue(HOST_KEY, ""), "").trim();
        int port = parsePort(configs.getConfigValue(PORT_KEY, "465"));
        String security = Objects.toString(configs.getConfigValue(SECURITY_KEY, "TLS"), "TLS").trim().toUpperCase(Locale.ROOT);
        String username = Objects.toString(configs.getConfigValue(USERNAME_KEY, ""), "").trim();
        String password = Objects.toString(configs.getConfigValue(PASSWORD_KEY, ""), "");
        String fromAddress = Objects.toString(configs.getConfigValue(FROM_ADDRESS_KEY, ""), "").trim();
        String senderName = Objects.toString(configs.getConfigValue(SENDER_NAME_KEY, "运营通知"), "运营通知").trim();
        List<String> recipients = parseStoredRecipients(configs.getConfigValue(RECIPIENTS_KEY, "[]"));
        List<String> events = parseStoredEvents(configs.getConfigValue(EVENTS_KEY, JSON.toJSONString(defaultEvents())));
        return new SettingsData(enabled, host, port, security, username, password, fromAddress, senderName,
                recipients, events);
    }

    private void validateDeliveryConfiguration(SettingsData data) {
        if (data.host().isBlank() || data.fromAddress().isBlank() || data.password().isBlank()
                || data.recipients().isEmpty()) throw new BusinessException(409, "SMTP 配置不完整，请先保存有效配置");
    }

    private boolean isDeliveryConfigured(SettingsData data) {
        return !data.host().isBlank() && !data.fromAddress().isBlank() && !data.password().isBlank();
    }

    private List<String> normalizeEvents(List<String> requested) {
        if (requested == null) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : requested) {
            String event = cleanText(value).toUpperCase(Locale.ROOT);
            if (event.isBlank()) continue;
            if (!templates.supports(event)) throw new BusinessException(400, "包含未知的邮件通知事件");
            normalized.add(event);
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeEmails(Collection<String> values, String label, boolean requireOne) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                for (String candidate : value.split("[,;\\r\\n]+")) {
                    String address = normalizeEmail(candidate, label);
                    if (!address.isBlank()) normalized.add(address.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (normalized.size() > MAX_RECIPIENTS) throw new BusinessException(400, label + "最多配置 20 个");
        if (requireOne && normalized.isEmpty()) throw new BusinessException(400, "请至少填写一个" + label);
        return List.copyOf(normalized);
    }

    private List<String> normalizeRequestedRecipients(Object value) {
        if (value == null) return List.of();
        if (value instanceof String text) return normalizeEmails(List.of(text), "测试收件邮箱", false);
        if (value instanceof Collection<?> collection) {
            List<String> values = collection.stream().filter(Objects::nonNull).map(Object::toString).toList();
            return normalizeEmails(values, "测试收件邮箱", false);
        }
        throw new BusinessException(400, "测试收件邮箱格式不正确");
    }

    private String normalizeEmail(String value, String label) {
        String address = cleanText(value);
        if (address.isBlank()) return "";
        if (address.length() > 254) throw new BusinessException(400, label + "格式不正确");
        try {
            InternetAddress[] parsed = InternetAddress.parse(address, true);
            if (parsed.length != 1 || parsed[0].getPersonal() != null) throw new IllegalArgumentException();
            parsed[0].validate();
            return parsed[0].getAddress();
        } catch (Exception error) {
            throw new BusinessException(400, label + "格式不正确");
        }
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.chars().anyMatch(Character::isISOControl)) throw new BusinessException(400, "配置内容包含无效字符");
        return text;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : 465;
        } catch (Exception ignored) {
            return 465;
        }
    }

    private List<String> parseStoredList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<String> values = JSON.parseArray(value, String.class);
            return values == null ? List.of() : values.stream().filter(Objects::nonNull)
                    .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> parseStoredRecipients(String value) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        for (String item : parseStoredList(value)) {
            try {
                String address = normalizeEmail(item, "收件邮箱");
                if (!address.isBlank()) recipients.add(address.toLowerCase(Locale.ROOT));
            } catch (BusinessException ignored) {
                // Invalid legacy values stay out of delivery and never reach SMTP.
            }
        }
        return List.copyOf(recipients);
    }

    private List<String> parseStoredEvents(String value) {
        LinkedHashSet<String> events = new LinkedHashSet<>();
        for (String item : parseStoredList(value)) {
            String event = item.toUpperCase(Locale.ROOT);
            if (templates.supports(event)) events.add(event);
        }
        return List.copyOf(events);
    }

    private static List<String> defaultEvents() {
        return new EmailNotificationTemplate().availableEvents().stream()
                .map(EmailNotificationTemplate.EventDescriptor::id).toList();
    }

    private void writeConfig(String key, String value, String type, String name, String remark) {
        SysConfig existing = configs.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (existing == null) {
            ensureConfig(key, value, type, name, remark);
            return;
        }
        existing.setConfigValue(value == null ? "" : value);
        configs.updateById(existing);
    }

    private void ensureConfig(String key, String value, String type, String name, String remark) {
        if (configs.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key))) return;
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setValueType(type);
        config.setConfigName(name);
        config.setConfigGroup("邮件通知");
        config.setRemark(remark);
        config.setCreatedAt(LocalDateTime.now(clock));
        config.setUpdatedAt(LocalDateTime.now(clock));
        configs.save(config);
    }

    private String findOrderNo(Long orderId) {
        if (orderId == null) return null;
        try {
            return jdbc.queryForObject("SELECT order_no FROM `order` WHERE id=?", String.class, orderId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String eventHash(String type, String userType, Long userId, Long orderId, String message) {
        return sha256(String.join("\n", type, Objects.toString(userType, ""), Objects.toString(userId, ""),
                Objects.toString(orderId, ""), Objects.toString(message, "")));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static LocalDateTime nextAttemptTime(LocalDateTime now, LocalDateTime createdAt, int attempt) {
        long delay = Math.min(3600L, 30L * (1L << Math.min(6, Math.max(0, attempt - 1))));
        LocalDateTime candidate = now.plusSeconds(delay);
        if (createdAt != null) candidate = candidate.isAfter(createdAt.plus(MAX_AGE))
                ? createdAt.plus(MAX_AGE) : candidate;
        return candidate;
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private static String failureCode(Exception error) {
        if (error instanceof EmailDeliveryException delivery) return delivery.code;
        Throwable current = error;
        while (current != null) {
            if (current instanceof MailAuthenticationException) return "SMTP_AUTH_FAILED";
            if (current instanceof AuthenticationFailedException) return "SMTP_AUTH_FAILED";
            if (current instanceof SSLException) return "SMTP_TLS_FAILED";
            if (current instanceof MessagingException messaging && messaging.getNextException() != null)
                current = messaging.getNextException();
            else current = current.getCause();
        }
        return "SMTP_SEND_FAILED";
    }

    record SettingsData(boolean enabled, String host, int port, String security, String username,
                        String password, String fromAddress, String senderName, List<String> recipients,
                        List<String> events) {}

    private static final class EmailDeliveryException extends Exception {
        private final String code;
        private EmailDeliveryException(String code) {
            this.code = code;
        }
    }
}
