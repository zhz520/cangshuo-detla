package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatCallbackServiceTest {
    private JdbcTemplate jdbc;
    private WechatCallbackService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_callback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS wechat_message");
        jdbc.execute("DROP TABLE IF EXISTS wechat_autoreply_rule");
        jdbc.execute("DROP TABLE IF EXISTS wechat_channel_qr");
        jdbc.execute("CREATE TABLE wechat_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(36)," +
                "msg_id VARCHAR(128),openid VARCHAR(128),direction VARCHAR(16),msg_type VARCHAR(24),content TEXT," +
                "event_key VARCHAR(128),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(account_id,msg_id))");
        jdbc.execute("CREATE TABLE wechat_autoreply_rule(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(36)," +
                "rule_type VARCHAR(16),keyword VARCHAR(80),match_mode VARCHAR(16),reply_type VARCHAR(16),content TEXT," +
                "media_id VARCHAR(128),priority INT,enabled TINYINT)");
        jdbc.execute("CREATE TABLE wechat_channel_qr(id VARCHAR(36) PRIMARY KEY,account_id VARCHAR(36),scene VARCHAR(64),scans INT DEFAULT 0)");
        jdbc.update("INSERT INTO wechat_autoreply_rule(account_id,rule_type,keyword,match_mode,reply_type,content,priority,enabled) " +
                "VALUES('acc','KEYWORD','你好','CONTAINS','TEXT','欢迎咨询','10',1)");
        WechatAccountService accounts = mock(WechatAccountService.class);
        when(accounts.callbackToken("acc")).thenReturn("callback-token");
        when(accounts.appId("acc")).thenReturn("wx1234567890");
        when(accounts.aesKey("acc")).thenReturn("");
        service = new WechatCallbackService(jdbc, accounts);
    }

    @Test
    void verifiesPlainCallbackAndRepliesToMatchingKeyword() throws Exception {
        String timestamp = "1720000000", nonce = "nonce", signature = signature("callback-token", timestamp, nonce);
        assertThat(service.verify("acc", signature, timestamp, nonce, "echo-ok")).isEqualTo("echo-ok");
        String body = "<xml><ToUserName><![CDATA[gh_account]]></ToUserName><FromUserName><![CDATA[openid_1234567890]]></FromUserName>" +
                "<CreateTime>1720000000</CreateTime><MsgType><![CDATA[text]]></MsgType><Content><![CDATA[你好，我想咨询]]></Content>" +
                "<MsgId>123456789</MsgId></xml>";
        String reply = service.handle("acc", signature, null, timestamp, nonce, null, body);
        assertThat(reply).contains("欢迎咨询").contains("[CDATA[openid_1234567890]]>").contains("[CDATA[gh_account]]>");
        assertThat(jdbc.queryForObject("SELECT content FROM wechat_message WHERE account_id='acc'", String.class)).contains("你好");
    }

    @Test
    void rejectsForgedSignatureBeforeReadingTheMessage() {
        assertThatThrownBy(() -> service.handle("acc", "bad", null, "1720000000", "nonce", null, "<xml/>"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403));
    }

    private static String signature(String token, String timestamp, String nonce, String... extra) throws Exception {
        var values = new java.util.ArrayList<>(java.util.List.of(token, timestamp, nonce));
        values.addAll(Arrays.asList(extra));
        String joined = values.stream().sorted().collect(Collectors.joining());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }
}
