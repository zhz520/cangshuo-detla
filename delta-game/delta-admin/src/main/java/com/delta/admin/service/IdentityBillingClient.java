package com.delta.admin.service;

import com.delta.admin.platform.PlatformProperties;
import com.delta.admin.platform.PlatformService;
import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Client for the authority-issued, metered two-factor identity check. */
@Service
@RequiredArgsConstructor
public class IdentityBillingClient {
    private final PlatformProperties properties;
    private final PlatformService platform;
    private final ObjectMapper json;
    private final com.delta.common.sms.service.SmsGatewayClient gateway;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public record Result(boolean matched, String status, long balanceCents) {}

    public boolean configured() {
        try { gateway.signedJsonRequest(endpoint("verify"), "{}"); return true; } catch (Exception error) { return false; }
    }

    public Result verify(String realName, String idNumber, String idempotencyKey) {
        var body = clientBody();
        body.put("idempotencyKey", idempotencyKey);
        body.put("realName", realName);
        body.put("idNumber", idNumber);
        JsonNode payload = request("verify", body);
        if (!payload.path("matched").isBoolean()) throw new BusinessException(502, "实名核验服务返回异常");
        return new Result(payload.path("matched").asBoolean(), payload.path("status").asText(""),
                payload.path("balanceCents").asLong(0));
    }

    public JsonNode account() { return request("account", clientBody()); }

    public JsonNode checkout(String id) {
        if (!id.matches("[0-9a-f-]{36}")) throw new BusinessException(400, "套餐编号无效");
        return request("packages/" + id + "/checkout", clientBody());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode clientBody() {
        var license = platform.reportPayload().license();
        if (license == null) throw new BusinessException(409, "当前实例尚未完成授权接入");
        var body = json.createObjectNode();
        body.set("license", json.valueToTree(license));
        body.put("version", platform.overview().version());
        return body;
    }

    private JsonNode request(String path, JsonNode body) {
        try {
            URI endpoint = endpoint(path);
            var request = gateway.signedJsonRequest(endpoint, json.writeValueAsString(body))
                    .timeout(Duration.ofSeconds(12))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload;
            try { payload = json.readTree(response.body()); }
            catch (Exception error) { throw new BusinessException(502, "实名核验服务返回异常"); }
            if (response.statusCode() >= 400) {
                String message = payload.path("message").asText("实名核验服务暂时不可用");
                int status = response.statusCode() == 409 ? 409
                        : response.statusCode() == 429 ? 429
                        : response.statusCode() == 403 ? 403 : 502;
                throw new BusinessException(status, safeMessage(message));
            }
            if (payload == null || !payload.isObject()) throw new BusinessException(502, "实名核验服务返回异常");
            if (path.endsWith("/checkout")) {
                URI target = URI.create(payload.path("checkoutUrl").asText());
                if (!java.util.Objects.equals(target.getScheme(), endpoint.getScheme())
                        || !java.util.Objects.equals(target.getRawAuthority(), endpoint.getRawAuthority())
                        || !target.getPath().matches("/pay/identity-package/[A-Za-z0-9_-]{43}"))
                    throw new BusinessException(502, "实名套餐支付地址无效");
            }
            return payload;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "实名核验服务请求已中断");
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(502, "实名核验服务连接失败，请稍后重试");
        }
    }

    private URI endpoint(String action) {
        String raw = properties.getReportUrl();
        if (raw == null || raw.isBlank()) throw new IllegalStateException("authority report URL missing");
        URI report = URI.create(raw.trim());
        boolean secure = "https".equalsIgnoreCase(report.getScheme());
        boolean loopback = "localhost".equalsIgnoreCase(report.getHost()) || "127.0.0.1".equals(report.getHost());
        if ((!secure && !loopback) || report.getHost() == null || report.getUserInfo() != null || report.getFragment() != null)
            throw new IllegalStateException("authority report URL is not allowed");
        String path = report.getPath() == null ? "" : report.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0) throw new IllegalStateException("authority report path invalid");
        try {
            return new URI(report.getScheme(), report.getUserInfo(), report.getHost(), report.getPort(),
                    path.substring(0, slash + 1) + "identity/" + action, null, null);
        } catch (Exception error) {
            throw new IllegalStateException("authority report URL invalid", error);
        }
    }

    private static String safeMessage(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return "实名核验服务暂时不可用";
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
