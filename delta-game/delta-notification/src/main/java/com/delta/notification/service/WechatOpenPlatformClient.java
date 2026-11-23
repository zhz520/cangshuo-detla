package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.platform.PlatformService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Third-party credentials stay in Authority. This server uses the instance gateway. */
@Service
@RequiredArgsConstructor
public class WechatOpenPlatformClient {
    private static final String STATE = "wechat:open-attempt:";
    private static final String LAUNCH = "/api/wechat/open-platform/launch/";
    private final PlatformService platform;
    private final SmsGatewayClient gateway;
    private final RedisService redis;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public record Availability(boolean ready, String reason) {}
    public record Authorization(String state, String authorizationUrl, String expiresAt) {}
    public record Progress(String status, Map<String, Object> account, boolean opened) {}

    public Availability status() {
        if (!gateway.configured()) return new Availability(false, "当前实例尚未配置授权中心接入凭据");
        var response = request("status", Map.of());
        return new Availability(response.path("ready").asBoolean(), response.path("reason").asText(""));
    }

    public Authorization start() {
        var result = request("start", Map.of());
        String state = result.path("state").asText("");
        String url = result.path("authorizationUrl").asText("");
        if (!validState(state) || !url.equals(origin() + LAUNCH + state))
            throw new BusinessException(502, "授权中心返回的扫码地址无效");
        redis.set(STATE + state, SecurityUtils.getUserId().toString(), 10, TimeUnit.MINUTES);
        return new Authorization(state, url, result.path("expiresAt").asText(""));
    }

    public Progress progress(String state, WechatAccountService accounts) {
        if (!validState(state)) throw new BusinessException(404, "授权会话不存在");
        if (!SecurityUtils.getUserId().toString().equals(redis.get(STATE + state)))
            throw new BusinessException(404, "授权会话不存在或已过期");
        var result = request("progress", Map.of("state", state));
        String status = result.path("status").asText();
        if ("AUTHORIZED".equals(status)) {
            var item = result.path("account");
            return new Progress(status, accounts.importAuthorizedAccount(item.path("appId").asText(), item.path("name").asText()), true);
        }
        if (!java.util.Set.of("PENDING", "PROCESSING", "EXPIRED", "FAILED").contains(status))
            throw new BusinessException(502, "授权中心返回的扫码状态无效");
        return new Progress(status, null, result.path("opened").asBoolean());
    }

    public String accessToken(String appId) {
        String token = request("token", Map.of("appId", appId)).path("accessToken").asText("");
        if (!token.matches("[A-Za-z0-9_-]{1,2048}"))
            throw new BusinessException(502, "授权中心未返回有效的公众号访问凭据");
        return token;
    }

    private static boolean validState(String state) { return state != null && state.matches("[A-Za-z0-9_-]{43}"); }
    private String origin() { return platform.authorityOrigin().replaceAll("/+$", ""); }
    private JsonNode request(String action, Map<String, String> values) {
        try {
            var identity = platform.reportPayload();
            if (identity == null || identity.license() == null) throw new BusinessException(403, "当前实例尚未接入授权中心");
            var body = json.createObjectNode();
            body.set("license", json.valueToTree(identity.license()));
            values.forEach(body::put);
            var req = gateway.signedJsonRequest(URI.create(origin() + "/api/client/wechat-open/" + action),
                    json.writeValueAsString(body)).timeout(Duration.ofSeconds(18)).build();
            var response = http.send(req, HttpResponse.BodyHandlers.ofString());
            var result = json.readTree(response.body());
            if (result == null || !result.isObject()) throw new BusinessException(502, "授权中心返回格式无效");
            if (response.statusCode() != 200) {
                String message = result.path("message").asText("微信开放平台暂不可用");
                int code = response.statusCode();
                if (code == 401) throw new BusinessException(503, "授权中心实例凭据无效，请联系平台运营");
                throw new BusinessException(code >= 400 && code <= 599 ? code : 502,
                        message.substring(0, Math.min(message.length(), 150)));
            }
            return result;
        } catch (BusinessException error) { throw error; }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "微信开放平台请求已取消");
        } catch (Exception error) {
            throw new BusinessException(502, "微信开放平台连接失败，请稍后重试");
        }
    }
}
