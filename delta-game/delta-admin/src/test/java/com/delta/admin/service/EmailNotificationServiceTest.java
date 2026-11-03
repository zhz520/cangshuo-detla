package com.delta.admin.service;

import com.alibaba.fastjson2.JSON;
import com.delta.admin.platform.PlatformService;
import com.delta.common.event.BusinessEvent;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationServiceTest {
    private JdbcTemplate jdbc;
    private SysConfigService configs;
    private PlatformService platform;
    private Transport transport;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:email_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE email_notice_queue (" +
                "id VARCHAR(36) PRIMARY KEY,event_type VARCHAR(64) NOT NULL,event_hash CHAR(64) NOT NULL," +
                "recipient VARCHAR(254) NOT NULL,subject VARCHAR(255) NOT NULL,plain_body TEXT NOT NULL," +
                "html_body TEXT NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'PENDING',attempts INT NOT NULL DEFAULT 0," +
                "last_error VARCHAR(100),next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,sent_at TIMESTAMP," +
                "CONSTRAINT uk_email_notice_event_recipient UNIQUE(event_hash,recipient))");
        configs = mock(SysConfigService.class);
        platform = mock(PlatformService.class);
        transport = mock(Transport.class);
        when(platform.featureEnabled(EmailNotificationService.FEATURE_ID)).thenReturn(true);
        when(configs.getConfigValue(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void eventListenerDeduplicatesByEventAndRecipientAndHonorsSelection() throws Exception {
        configure("true", "[\"OPS@example.com\",\"ops@example.com\",\"billing@example.com\"]", "[\"ORDER_PAID\"]");
        EmailNotificationService service = service(successfulSender());
        BusinessEvent paid = new BusinessEvent(this, "ORDER_PAID", "USER", 7L, 100L, "订单已支付");

        service.onBusinessEvent(paid);
        service.onBusinessEvent(paid);
        service.onBusinessEvent(new BusinessEvent(this, "ORDER_ASSIGNED", "PLAYER", 3L, 100L, "订单已指派"));
        when(platform.featureEnabled(EmailNotificationService.FEATURE_ID)).thenReturn(false);
        service.onBusinessEvent(paid);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notice_queue", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT recipient FROM email_notice_queue ORDER BY recipient", String.class))
                .containsExactly("billing@example.com", "ops@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT event_hash) FROM email_notice_queue", Integer.class)).isEqualTo(1);
    }

    @Test
    void testQueueAndDeliveryUsePlainAndHtmlPartsThroughInstrumentedTransport() throws Exception {
        String allEvents = JSON.toJSONString(new EmailNotificationTemplate().availableEvents()
                .stream().map(event -> event.id()).toList());
        configure("true", "[\"ops@example.com\"]", allEvents);
        EmailNotificationService service = service(successfulSender());

        int queued = service.test(new EmailNotificationService.TestRequest(List.of("ops@example.com", "OPS@example.com", "audit@example.com")));
        assertThat(queued).isEqualTo(2);
        service.deliver();

        ArgumentCaptor<MimeMessage> messages = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport, org.mockito.Mockito.times(2)).sendMessage(messages.capture(), any());
        for (MimeMessage message : messages.getAllValues()) {
            String body = content(message);
            assertThat(body).contains("SMTP 测试邮件").contains("<html").contains("这是一封邮件通知配置测试")
                    .contains("运营").contains("AUTOMATED NOTICE");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notice_queue WHERE status='SENT' AND sent_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failuresUseSafeCodesExpireOldRowsAndCanBeRetried() throws Exception {
        configure("true", "[\"ops@example.com\"]", "[\"ORDER_PAID\"]");
        EmailNotificationService service = service(successfulSender());
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO email_notice_queue(id,event_type,event_hash,recipient,subject,plain_body,html_body,status) " +
                        "VALUES(?,?,?,?,?,?,?,'PENDING')", id, "ORDER_PAID", "a".repeat(64), "ops@example.com",
                "订单通知", "password=do-not-leak body=do-not-leak", "<html>do-not-leak</html>");
        Timestamp expiredAt = Timestamp.valueOf("2026-10-31 09:00:00");
        jdbc.update("INSERT INTO email_notice_queue(id,event_type,event_hash,recipient,subject,plain_body,html_body,status,created_at,next_at) " +
                        "VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",
                UUID.randomUUID().toString(), "ORDER_PAID", "b".repeat(64), "ops@example.com", "过期", "过期", "<html>过期</html>",
                expiredAt, expiredAt);
        doThrow(new AuthenticationFailedException("secret password and message content"))
                .when(transport).sendMessage(any(), any());

        for (int attempt = 1; attempt <= 5; attempt++) {
            service.deliver();
            if (attempt < 5) jdbc.update("UPDATE email_notice_queue SET next_at=CURRENT_TIMESTAMP WHERE id=?", id);
        }

        var failed = jdbc.queryForMap("SELECT status,attempts,last_error FROM email_notice_queue WHERE id=?", id);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(5);
        assertThat(String.valueOf(failed.get("last_error"))).startsWith("SMTP_").doesNotContain("secret", "do-not-leak");
        assertThat(jdbc.queryForObject("SELECT status FROM email_notice_queue WHERE event_hash=?", String.class, "b".repeat(64)))
                .isEqualTo("EXPIRED");

        service.retry(id);
        assertThat(jdbc.queryForMap("SELECT status,attempts,last_error FROM email_notice_queue WHERE id=?", id))
                .containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("last_error", null);
    }

    @Test
    void settingsNormalizeRecipientsAndEventsAndNeverReturnPassword() throws Exception {
        when(configs.getOne(any())).thenReturn(null);
        when(configs.exists(any())).thenReturn(false);
        EmailNotificationService service = service(successfulSender());

        EmailNotificationService.Settings result = service.save(new EmailNotificationService.SettingsRequest(
                true, "smtp.example.com", 465, "TLS", "mailer@example.com", "noreply@example.com", "运营",
                List.of("OPS@example.com", "ops@example.com; audit@example.com"),
                List.of("ORDER_PAID", "ORDER_PAID", "NEW_REVIEW"), "smtp-secret"));

        ArgumentCaptor<SysConfig> saved = ArgumentCaptor.forClass(SysConfig.class);
        verify(configs, org.mockito.Mockito.atLeast(10)).save(saved.capture());
        String recipients = saved.getAllValues().stream().filter(item -> EmailNotificationService.RECIPIENTS_KEY.equals(item.getConfigKey()))
                .findFirst().orElseThrow().getConfigValue();
        String events = saved.getAllValues().stream().filter(item -> EmailNotificationService.EVENTS_KEY.equals(item.getConfigKey()))
                .findFirst().orElseThrow().getConfigValue();
        String password = saved.getAllValues().stream().filter(item -> EmailNotificationService.PASSWORD_KEY.equals(item.getConfigKey()))
                .findFirst().orElseThrow().getConfigValue();
        assertThat(JSON.parseArray(recipients, String.class)).containsExactly("ops@example.com", "audit@example.com");
        assertThat(JSON.parseArray(events, String.class)).containsExactly("ORDER_PAID", "NEW_REVIEW");
        assertThat(password).isEqualTo("smtp-secret");
        assertThat(result.password()).isEmpty();
    }

    private void configure(String enabled, String recipients, String events) {
        when(configs.getConfigValue(EmailNotificationService.ENABLED_KEY, "false")).thenReturn(enabled);
        when(configs.getConfigValue(EmailNotificationService.HOST_KEY, "")).thenReturn("smtp.example.com");
        when(configs.getConfigValue(EmailNotificationService.PORT_KEY, "465")).thenReturn("465");
        when(configs.getConfigValue(EmailNotificationService.SECURITY_KEY, "TLS")).thenReturn("TLS");
        when(configs.getConfigValue(EmailNotificationService.USERNAME_KEY, "")).thenReturn("mailer@example.com");
        when(configs.getConfigValue(EmailNotificationService.PASSWORD_KEY, "")).thenReturn("smtp-secret");
        when(configs.getConfigValue(EmailNotificationService.FROM_ADDRESS_KEY, "")).thenReturn("noreply@example.com");
        when(configs.getConfigValue(EmailNotificationService.SENDER_NAME_KEY, "运营通知")).thenReturn("运营");
        when(configs.getConfigValue(EmailNotificationService.RECIPIENTS_KEY, "[]")).thenReturn(recipients);
        when(configs.getConfigValue(eq(EmailNotificationService.EVENTS_KEY), anyString())).thenReturn(events);
    }

    private EmailNotificationService service(JavaMailSender sender) {
        return new EmailNotificationService(jdbc, configs, platform, new EmailNotificationTemplate(),
                Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC)) {
            @Override
            protected JavaMailSender createMailSender(SettingsData settings) {
                return sender;
            }
        };
    }

    private JavaMailSender successfulSender() throws Exception {
        doAnswer(invocation -> null).when(transport).sendMessage(any(), any());
        return new JavaMailSenderImpl() {
            @Override
            protected Transport getTransport(Session session) {
                return transport;
            }
        };
    }

    private static String content(MimeMessage message) throws Exception {
        List<String> values = new ArrayList<>();
        collect(message.getContent(), values);
        return String.join("\n", values);
    }

    private static void collect(Object content, List<String> values) throws Exception {
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                collect(part.getContent(), values);
            }
        } else if (content != null) {
            values.add(content.toString());
        }
    }
}
