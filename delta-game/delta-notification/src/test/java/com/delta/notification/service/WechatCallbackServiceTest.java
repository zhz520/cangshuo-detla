package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatCallbackServiceTest {
    private static final byte[] AES_KEY = "fixture-key-for-wechat-32-bytes!".getBytes(StandardCharsets.US_ASCII);
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
        when(accounts.aesKey("acc")).thenReturn(Base64.getEncoder().withoutPadding().encodeToString(AES_KEY));
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

    @Test
    void standardEncryptedCallbackCanReturnAnEncryptedKeywordReply() throws Exception {
        String timestamp = "1720000000", nonce = "nonce";
        byte[] xml = ("<xml><ToUserName>gh_account</ToUserName><FromUserName>openid_1234567890</FromUserName>" +
                "<CreateTime>1720000000</CreateTime><MsgType>text</MsgType><Content>你好</Content><MsgId>456</MsgId></xml>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] appId = "wx1234567890".getBytes(StandardCharsets.US_ASCII);
        int packetSize = 20 + xml.length + appId.length;
        int pad = 32 - packetSize % 32;
        var packet = ByteBuffer.allocate(packetSize + pad);
        packet.put(new byte[16]).putInt(xml.length).put(xml).put(appId);
        byte[] padding = new byte[pad];
        Arrays.fill(padding, (byte) pad);
        packet.put(padding);
        String inbound = Base64.getEncoder().encodeToString(crypto(Cipher.ENCRYPT_MODE, packet.array()));
        String signed = signature("callback-token", timestamp, nonce, inbound);
        String output = service.handle("acc", null, signed, timestamp, nonce, "aes",
                "<xml><Encrypt><![CDATA[" + inbound + "]]></Encrypt></xml>");

        String encryptedReply = output.split("<Encrypt><!\\[CDATA\\[")[1].split("]]>")[0];
        assertThat(output).contains(signature("callback-token", timestamp, nonce, encryptedReply));
        var reply = ByteBuffer.wrap(crypto(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(encryptedReply)));
        reply.position(16);
        byte[] message = new byte[reply.getInt()];
        reply.get(message);
        assertThat(new String(message, StandardCharsets.UTF_8)).contains("欢迎咨询", "openid_1234567890");
    }

    private static byte[] crypto(int mode, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(AES_KEY, "AES"), new IvParameterSpec(AES_KEY, 0,16));
        return cipher.doFinal(input);
    }

    private static String signature(String token, String timestamp, String nonce, String... extra) throws Exception {
        var values = new java.util.ArrayList<>(java.util.List.of(token, timestamp, nonce));
        values.addAll(Arrays.asList(extra));
        String joined = values.stream().sorted().collect(Collectors.joining());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }
}
