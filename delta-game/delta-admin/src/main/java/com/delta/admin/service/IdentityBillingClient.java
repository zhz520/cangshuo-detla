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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public record Result(boolean matched, String status, long balanceCents) {}

    public boolean configured() {
        try { endpoint(); return true; } catch (Exception error) { return false; }
    }

    public Result verify(String realName, String idNumber, String idempotencyKey) {
        URI endpoint = endpoint();
        var license = platform.reportPayload().license();
        if (license == null) throw new BusinessException(409, "当前实例尚未完成授权接入");
        var body = json.createObjectNode();
        body.set("license", json.valueToTree(license));
        body.put("version", platform.overview().version());
        body.put("idempotencyKey", idempotencyKey);
        body.put("realName", realName);
        body.put("idNumber", idNumber);
        try {
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
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
            if (!payload.path("matched").isBoolean()) throw new BusinessException(502, "实名核验服务返回异常");
            return new Result(payload.path("matched").asBoolean(), payload.path("status").asText(""),
                    payload.path("balanceCents").asLong(0));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(502, "实名核验服务连接失败，请稍后重试");
        }
    }

    private URI endpoint() {
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
                    path.substring(0, slash + 1) + "identity/verify", null, null);
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
