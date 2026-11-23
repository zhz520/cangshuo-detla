package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WechatCallbackService {
    private final JdbcTemplate jdbc;
    private final WechatAccountService accounts;
    private final SecureRandom random = new SecureRandom();

    public String verify(String accountId, String signature, String timestamp, String nonce, String echostr) {
        String token = accounts.callbackToken(accountId);
        if (token.isBlank()) throw new BusinessException(503, "公众号回调尚未配置");
        if (!safeEquals(signature(token, timestamp, nonce), signature)) throw new BusinessException(403, "公众号回调签名无效");
        return echostr == null ? "" : echostr;
    }

    public String handle(String accountId, String signature, String msgSignature, String timestamp, String nonce,
                         String encryptType, String body) {
        String token = accounts.callbackToken(accountId);
        if (token.isBlank()) throw new BusinessException(503, "公众号回调尚未配置");
        String plain = body == null ? "" : body;
        boolean encrypted = "aes".equalsIgnoreCase(encryptType);
        if (encrypted) {
            String encrypt = xmlValue(plain, "Encrypt");
            if (encrypt.isBlank() || !safeEquals(signature(token, timestamp, nonce, encrypt), msgSignature))
                throw new BusinessException(403, "公众号加密回调签名无效");
            plain = decrypt(accounts.aesKey(accountId), encrypt, accounts.appId(accountId));
        } else {
            if (!safeEquals(signature(token, timestamp, nonce), signature)) throw new BusinessException(403, "公众号回调签名无效");
        }
        String toUser = xmlValue(plain, "ToUserName");
        String fromUser = xmlValue(plain, "FromUserName");
        String msgType = xmlValue(plain, "MsgType").toLowerCase(Locale.ROOT);
        String msgId = xmlValue(plain, "MsgId");
        String event = xmlValue(plain, "Event").toLowerCase(Locale.ROOT);
        String eventKey = xmlValue(plain, "EventKey");
        String content = xmlValue(plain, "Content");
        if (fromUser.isBlank() || !fromUser.matches("[A-Za-z0-9_-]{10,128}") || msgType.isBlank()) return "success";
        record(accountId, msgId.isBlank() ? null : msgId, fromUser, msgType, content, eventKey);
        if (!eventKey.isBlank()) jdbc.update("UPDATE wechat_channel_qr SET scans=scans+1 WHERE account_id=? AND scene=?", accountId, normalizeScene(eventKey));
        String reply = replyRule(accountId, msgType, event, content);
        if (reply == null || reply.isBlank()) return "success";
        String replyXml = textReply(toUser, fromUser, reply);
        if (!encrypted) return replyXml;
        String encrypt = encrypt(accounts.aesKey(accountId), replyXml, accounts.appId(accountId));
        String replySignature = signature(token, timestamp, nonce, encrypt);
        return "<xml><Encrypt><![CDATA[" + encrypt + "]]></Encrypt><MsgSignature><![CDATA[" + replySignature +
                "]]></MsgSignature><TimeStamp>" + timestamp + "</TimeStamp><Nonce><![CDATA[" + nonce + "]]></Nonce></xml>";
    }

    private String replyRule(String accountId, String msgType, String event, String content) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT rule_type,keyword,match_mode,reply_type,content FROM wechat_autoreply_rule WHERE account_id=? AND enabled=1 ORDER BY priority DESC,id",
                accountId);
        if ("event".equals(msgType) && "subscribe".equals(event)) {
            for (var rule : rules) if ("SUBSCRIBE".equals(rule.get("rule_type")) && "TEXT".equals(rule.get("reply_type"))) return text(rule.get("content"));
        }
        if ("text".equals(msgType)) {
            for (var rule : rules) {
                if (!"KEYWORD".equals(rule.get("rule_type")) || !"TEXT".equals(rule.get("reply_type"))) continue;
                String keyword = text(rule.get("keyword")), candidate = content == null ? "" : content.trim();
                if (keyword.isBlank()) continue;
                boolean matches = "EXACT".equals(rule.get("match_mode")) ? candidate.equals(keyword) : candidate.contains(keyword);
                if (matches) return text(rule.get("content"));
            }
        }
        for (var rule : rules) if ("DEFAULT".equals(rule.get("rule_type")) && "TEXT".equals(rule.get("reply_type"))) return text(rule.get("content"));
        return null;
    }

    private void record(String accountId, String msgId, String openid, String msgType, String content, String eventKey) {
        try {
            jdbc.update("INSERT INTO wechat_message(account_id,msg_id,openid,msg_type,content,event_key) VALUES(?,?,?,?,?,?)",
                    accountId, msgId, openid, msgType, truncate(content, 4000), truncate(eventKey, 128));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // WeChat retries and duplicate deliveries must not create a second message.
        }
    }

    private String textReply(String toUser, String fromUser, String content) {
        return "<xml><ToUserName><![CDATA[" + cdata(fromUser) + "]]></ToUserName><FromUserName><![CDATA[" + cdata(toUser) +
                "]]></FromUserName><CreateTime>" + (System.currentTimeMillis() / 1000) +
                "</CreateTime><MsgType><![CDATA[text]]></MsgType><Content><![CDATA[" + cdata(content) + "]]></Content></xml>";
    }

    private static String xmlValue(String xml, String tag) {
        if (xml == null || xml.isBlank()) return "";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var nodes = document.getElementsByTagName(tag);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        } catch (Exception error) {
            throw new BusinessException(400, "公众号回调报文格式不正确");
        }
    }

    private static String signature(String token, String timestamp, String nonce, String... extra) {
        List<String> values = new ArrayList<>(List.of(token, timestamp == null ? "" : timestamp, nonce == null ? "" : nonce));
        values.addAll(Arrays.asList(extra));
        Collections.sort(values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static boolean safeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String encrypt(String encodedKey, String plain, String appId) {
        try {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            byte[] message = plain.getBytes(StandardCharsets.UTF_8), app = appId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(16 + 4 + message.length + app.length);
            packet.put(randomBytes).putInt(message.length).put(message).put(app);
            byte[] padded = pkcs7(packet.array(), 32);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(encodedKey), new IvParameterSpec(Arrays.copyOfRange(key(encodedKey).getEncoded(), 0, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception error) { throw new BusinessException(500, "公众号回调加密失败"); }
    }

    private String decrypt(String encodedKey, String encrypted, String appId) {
        try {
            byte[] key = key(encodedKey).getEncoded(), packed = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(encodedKey), new IvParameterSpec(Arrays.copyOfRange(packed, 0, 16)));
            byte[] plain = unpkcs7(cipher.doFinal(Arrays.copyOfRange(packed, 16, packed.length)));
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            buffer.position(16);
            int length = buffer.getInt();
            if (length < 1 || length > buffer.remaining()) throw new IllegalArgumentException();
            byte[] message = new byte[length];
            buffer.get(message);
            byte[] actualApp = new byte[buffer.remaining()];
            buffer.get(actualApp);
            if (!appId.equals(new String(actualApp, StandardCharsets.UTF_8))) throw new IllegalArgumentException("appid mismatch");
            return new String(message, StandardCharsets.UTF_8);
        } catch (Exception error) { throw new BusinessException(400, "公众号加密回调解码失败"); }
    }

    private static SecretKeySpec key(String encodedKey) {
        if (encodedKey == null || encodedKey.length() < 43) throw new BusinessException(503, "公众号 EncodingAESKey 未配置");
        String normalized = encodedKey.trim();
        if (!normalized.endsWith("=")) normalized += "=";
        byte[] bytes = Base64.getDecoder().decode(normalized);
        if (bytes.length != 32) throw new BusinessException(503, "公众号 EncodingAESKey 格式不正确");
        return new SecretKeySpec(bytes, "AES");
    }

    private static byte[] pkcs7(byte[] value, int block) {
        int padding = block - value.length % block;
        byte[] result = Arrays.copyOf(value, value.length + padding);
        Arrays.fill(result, value.length, result.length, (byte) padding);
        return result;
    }

    private static byte[] unpkcs7(byte[] value) {
        int padding = value[value.length - 1] & 0xff;
        if (padding < 1 || padding > 32 || padding > value.length) throw new IllegalArgumentException();
        return Arrays.copyOf(value, value.length - padding);
    }

    private static String normalizeScene(String eventKey) {
        String value = eventKey == null ? "" : eventKey;
        int prefix = value.indexOf("_");
        return prefix >= 0 ? value.substring(prefix + 1) : value;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
    private static String cdata(String value) { return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>"); }
}
