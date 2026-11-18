package com.delta.common.sms.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Server-to-server client for the authority SMS gateway. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsGatewayClient {
    public record LegacySettings(String endpoint, String accessKeyId, boolean hasAccessKeySecret, String signName,
                                 String verificationTemplateCode, String playerFinishTemplateCode,
                                 String csMessageTemplateCode, String teammateInvitedTemplateCode) {}

    public static class SmsGatewayRejectedException extends BusinessException {
        public SmsGatewayRejectedException(String message) { super(503, message); }
    }
    public static class SmsGatewayUnknownException extends BusinessException {
        public SmsGatewayUnknownException(String message) { super(503, message); }
    }

    private static final String SEND_PATH = "/api/sms/v1/send";
    private static final String ACCOUNT_PATH = "/api/sms/v1/account";
    private final CrossModuleMapper configs;
    private final InstanceIdentityProvider instanceIdentity;
    private final GatewayCredentials credentials;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AliyunSmsService legacyProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public boolean enabled() {
        return "ALIYUN".equals(provider()) ? legacyEnabled()
                : "true".equalsIgnoreCase(value("sms.gateway.enabled")) && configured();
    }

    /** AUTHORITY or ALIYUN. An explicit mode wins; legacy keys remain readable for upgrades. */
    public String provider() {
        String mode = value("sms.provider.mode").toUpperCase(Locale.ROOT);
        if ("AUTHORITY".equals(mode) || "ALIYUN".equals(mode)) return mode;
        if ("true".equalsIgnoreCase(value("sms.gateway.managed"))
                || "true".equalsIgnoreCase(value("sms.gateway.enabled"))) return "AUTHORITY";
        return "true".equalsIgnoreCase(value("sms.aliyun.enabled")) ? "ALIYUN" : "AUTHORITY";
    }

    public boolean legacyConfigured() {
        return configuredValue("sms.aliyun.access_key_id")
                && configuredValue("sms.aliyun.access_key_secret")
                && configuredValue("sms.aliyun.sign_name");
    }

    public LegacySettings legacySettings() {
        return new LegacySettings(
                value("sms.aliyun.endpoint", "dysmsapi.aliyuncs.com"),
                value("sms.aliyun.access_key_id"),
                configuredValue("sms.aliyun.access_key_secret"),
                value("sms.aliyun.sign_name"),
                value("sms.aliyun.template_code.verification"),
                value("sms.aliyun.template_code.player_finish_order"),
                value("sms.aliyun.template_code.cs_message_reminder"),
                value("sms.aliyun.template_code.player_teammate_invited"));
    }

    // Only the explicitly selected Aliyun mode can use legacy credentials.
    public boolean legacyEnabled() {
        return legacyProvider != null && "ALIYUN".equals(provider())
                && "true".equalsIgnoreCase(value("sms.aliyun.enabled")) && legacyConfigured();
    }

    private void sendLegacy(String key, String phone, Map<String, String> variables) {
        String suffix = switch (key) {
            case "chat_player_finish_order" -> "player_finish_order";
            case "chat_cs_message_reminder" -> "cs_message_reminder";
            case "business_player_teammate_invited" -> "player_teammate_invited";
            default -> key != null && key.startsWith("otp_") ? "verification" : "";
        };
        String code = suffix.isEmpty() ? "" : value("sms.aliyun.template_code." + suffix, switch(suffix) {
            case "player_finish_order" -> "SMS_504575043";
            case "cs_message_reminder" -> "SMS_504365043";
            case "player_teammate_invited" -> "SMS_504775096";
            default -> "";
        });
        if (code.isBlank()) throw new SmsGatewayRejectedException("原短信模板未配置");
        sendLegacyTemplate(code, phone, variables);
    }

    public void sendLegacyTemplate(String code, String phone, Map<String, String> variables) {
        if (!legacyEnabled()) throw new SmsGatewayRejectedException("原短信服务已停用");
        sendLegacyProvider(code, phone, variables);
    }

    /** Sends a provider test without requiring the tenant switch to be enabled. */
    public void testAliyunTemplate(String phone) {
        if (phone == null || !phone.matches("1[3-9][0-9]{9}"))
            throw new BusinessException(400, "测试手机号格式不正确");
        String code = value("sms.aliyun.template_code.verification");
        if (!legacyConfigured() || code.isBlank())
            throw new BusinessException(409, "请先保存阿里云账号、签名和验证码模板 Code");
        try {
            sendLegacyProvider(code, phone, Map.of("code", "123456"));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(503, "测试短信发送失败，请检查阿里云配置");
        }
    }

    private void sendLegacyProvider(String code, String phone, Map<String, String> variables) {
        String access=value("sms.aliyun.access_key_id"), secret=value("sms.aliyun.access_key_secret"), sign=value("sms.aliyun.sign_name");
        if(access.isBlank() || access.startsWith("YOUR_") || secret.isBlank() || secret.startsWith("YOUR_") || sign.isBlank() || code.isBlank())
            throw new SmsGatewayRejectedException("原短信服务配置不完整");
        legacyProvider.sendTemplate(value("sms.aliyun.endpoint", "dysmsapi.aliyuncs.com"), access, secret, sign, code, phone, variables);
    }

    public boolean configured() {
        try {
            return !gatewayOrigin().isBlank() && !gatewaySecret().isBlank();
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** Paid services share the instance credential, not the tenant's SMS switch. */
    public HttpRequest.Builder signedJsonRequest(URI endpoint, String raw) {
        String origin = gatewayOrigin(), instanceId = resolveInstanceId(), secret = gatewaySecret();
        if (secret.isBlank() || !endpoint.toString().startsWith(origin + "/") || origin.isBlank())
            throw new BusinessException(503, "授权中心服务尚未接入");
        String timestamp = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        return HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("x-sms-instance", instanceId)
                .header("x-sms-timestamp", timestamp)
                .header("x-sms-nonce", nonce)
                .header("x-sms-signature", sign(secret, "POST", endpoint.getRawPath(), timestamp, nonce, "", raw))
                .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8));
    }

    /**
     * Sends one templated message through the authority gateway.
     * Callers must provide a stable idempotency key for a logical message.
     */
    public void send(String templateKey, String phone, Map<String, String> variables, String idempotencyKey) {
        if (!enabled()) throw new BusinessException(503, "短信网关未启用");
        if (templateKey == null || templateKey.isBlank()) throw new BusinessException(400, "短信模板未配置");
        if (phone == null || !phone.matches("1[3-9][0-9]{9}")) throw new BusinessException(400, "手机号格式不正确");
        if (legacyEnabled()) {
            sendLegacy(templateKey, phone, variables);
            return;
        }
        String baseUrl = gatewayOrigin();
        String secret = gatewaySecret();
        String instanceId = resolveInstanceId();
        if (baseUrl.isBlank() || secret.isBlank()) throw new BusinessException(503, "短信网关配置不完整");
        String requestKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey;
        if (!requestKey.matches("[A-Za-z0-9:_-]{8,128}")) throw new BusinessException(400, "短信幂等键格式不正确");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instanceId);
        payload.put("templateKey", templateKey);
        payload.put("phone", phone);
        payload.put("variables", variables == null ? Map.of() : variables);
        payload.put("clientVersion", value("sms.gateway.client-version", "1.16.5"));
        String raw = JSON.toJSONString(payload);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + SEND_PATH))
                    .timeout(Duration.ofMillis(timeoutMillis()))
                    .header("content-type", "application/json")
                    .header("x-sms-instance", instanceId)
                    .header("x-sms-timestamp", timestamp)
                    .header("x-sms-nonce", nonce)
                    .header("idempotency-key", requestKey);
            builder.header("x-sms-signature", sign(secret, "POST", SEND_PATH, timestamp, nonce, requestKey, raw));
            request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new BusinessException(503, "短信网关请求失败");
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsGatewayUnknownException("短信发送被中断，请稍后确认");
        } catch (Exception e) {
            throw new SmsGatewayUnknownException("短信发送结果待确认，请稍后查询");
        }
        JSONObject result = null;
        try {
            result = JSON.parseObject(response.body());
        } catch (Exception ignored) {
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() >= 500 && (result == null || response.statusCode() == 502 || response.statusCode() == 504))
                throw new SmsGatewayUnknownException("短信发送结果待确认，请稍后查询");
            String message = result == null ? "短信网关暂不可用" : result.getString("message");
            throw new BusinessException(response.statusCode() == 429 ? 429 : 503,
                    message == null || message.isBlank() ? "短信网关暂不可用" : message);
        }
        String status = result == null ? "" : result.getString("status");
        if ("ACCEPTED".equalsIgnoreCase(status) || "DELIVERED".equalsIgnoreCase(status)) {
            log.info("authority sms accepted, template={}, instance={}, request={}", templateKey, instanceId,
                    result.getString("requestId"));
            return;
        }
        if ("UNKNOWN".equalsIgnoreCase(status) || "RESERVED".equalsIgnoreCase(status)) {
            throw new SmsGatewayUnknownException("短信发送结果待确认，请稍后查询");
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            throw new SmsGatewayRejectedException("短信发送失败，请稍后重试");
        }
        throw new SmsGatewayUnknownException("短信发送结果待确认，请稍后查询");
    }

    /** Creates a signed checkout URL for one package product. */
    public JSONObject createPackageCheckout(String packageId) {
        if (!configured()) throw new BusinessException(503, "授权中心短信服务尚未接入");
        if (packageId == null || !packageId.matches("[a-f0-9-]{36}")) throw new BusinessException(400, "套餐编号格式不正确");
        String baseUrl = gatewayOrigin();
        String secret = gatewaySecret();
        String instanceId = resolveInstanceId();
        if (baseUrl.isBlank() || secret.isBlank()) throw new BusinessException(503, "短信网关配置不完整");
        String path = "/api/sms/v1/packages/" + packageId + "/checkout";
        String raw = "{}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMillis()))
                .header("content-type", "application/json")
                .header("x-sms-instance", instanceId)
                .header("x-sms-timestamp", timestamp)
                .header("x-sms-nonce", nonce);
        builder.header("x-sms-signature", sign(secret, "POST", path, timestamp, nonce, "", raw));
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "套餐购买请求被中断");
        } catch (Exception e) {
            throw new BusinessException(503, "套餐购买请求失败");
        }
        JSONObject result = null;
        try { result = JSON.parseObject(response.body()); } catch (Exception ignored) {}
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = result == null ? "套餐购买暂时不可用" : result.getString("message");
            throw new BusinessException(503, message == null || message.isBlank() ? "套餐购买暂时不可用" : message);
        }
        if (result == null || !validCheckoutUrl(baseUrl, result.getString("checkoutUrl")))
            throw new BusinessException(502, "短信套餐支付地址无效");
        return result;
    }

    /** Reads the project SMS account, current packages and active template keys. */
    public JSONObject account() {
        if (!configured()) throw new BusinessException(503, "授权中心短信服务尚未接入");
        String baseUrl = gatewayOrigin();
        String secret = gatewaySecret();
        String instanceId = resolveInstanceId();
        if (baseUrl.isBlank() || secret.isBlank()) throw new BusinessException(503, "短信网关配置不完整");
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + ACCOUNT_PATH))
                .timeout(Duration.ofMillis(timeoutMillis()))
                .header("x-sms-instance", instanceId)
                .header("x-sms-timestamp", timestamp)
                .header("x-sms-nonce", nonce);
        builder.header("x-sms-signature", sign(secret, "GET", ACCOUNT_PATH, timestamp, nonce, "", ""));
        HttpRequest request = builder
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "短信账户查询被中断");
        } catch (Exception e) {
            throw new BusinessException(503, "短信账户查询失败");
        }
        JSONObject result = null;
        try { result = JSON.parseObject(response.body()); } catch (Exception ignored) {}
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = result == null ? "短信账户查询失败" : result.getString("message");
            throw new BusinessException(response.statusCode() == 429 ? 429 : 503,
                    message == null || message.isBlank() ? "短信账户查询失败" : message);
        }
        if (result == null) throw new BusinessException(503, "短信账户返回格式无效");
        return result;
    }

    private String resolveInstanceId() {
        String platformId = instanceIdentity == null ? "" : String.valueOf(instanceIdentity.currentInstanceId()).trim();
        if (platformId.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")) return platformId;
        throw new BusinessException(503, "短信实例编号未配置或平台实例尚未初始化");
    }

    private long timeoutMillis() {
        try {
            return Math.max(1000, Math.min(30000, Long.parseLong(value("sms.gateway.timeout-ms", "8000"))));
        } catch (Exception ignored) {
            return 8000;
        }
    }

    private boolean validCheckoutUrl(String base, String url) {
        try {
            URI origin = URI.create(base), target = URI.create(url);
            return origin.getScheme().equals(target.getScheme())
                    && origin.getRawAuthority().equals(target.getRawAuthority())
                    && target.getPath().matches("/pay/sms-package/[A-Za-z0-9_-]{43}")
                    && target.getRawQuery() == null && target.getRawFragment() == null;
        } catch (RuntimeException error) { return false; }
    }

    private String gatewayOrigin() {
        String raw = instanceIdentity == null ? "" : java.util.Objects.toString(instanceIdentity.authorityOrigin(), "").trim();
        raw = raw.replaceAll("/+$", "");
        if (raw.isBlank()) return "";
        try {
            URI base = URI.create(raw);
            boolean secure = "https".equalsIgnoreCase(base.getScheme());
            if (!secure && !isLocalHost(base.getHost())) throw new BusinessException(503, "短信网关必须使用 HTTPS");
            if (base.getHost() == null || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null
                    || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath())))
                throw new IllegalArgumentException();
            return base.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + base.getRawAuthority();
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(503, "短信网关地址无效");
        }
    }

    private String gatewaySecret() {
        String id = resolveInstanceId();
        return credentials.secret(id);
    }

    private String sign(String secret, String method, String path, String timestamp, String nonce, String idempotencyKey, String raw) {
        try {
            String digest = hex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
            String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + idempotencyKey + "\n" + digest;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(503, "短信网关签名失败");
        }
    }

    private String value(String key) {
        return value(key, "");
    }

    private String value(String key, String fallback) {
        String current = configs.selectConfigValue(key);
        return current == null || current.isBlank() ? fallback : current.trim();
    }

    private boolean configuredValue(String key) {
        String current = value(key);
        return !current.isBlank() && !current.startsWith("YOUR_");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }
}
