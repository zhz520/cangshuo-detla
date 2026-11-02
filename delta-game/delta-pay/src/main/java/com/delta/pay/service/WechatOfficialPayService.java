package com.delta.pay.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 微信公众号网页授权与 JSAPI 支付辅助。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatOfficialPayService {
    private static final String STATE_PREFIX = "wxpay:official:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysConfigService configs;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record RuntimeConfig(boolean enabled, String appId, String appSecret, String returnOrigin) {
        public boolean ready() {
            return enabled && !blank(appId) && !blank(appSecret) && !blank(returnOrigin);
        }
    }

    public record CallbackState(String paymentNo, Long userId, String kind, String returnPath, String returnOrigin) {}

    public record OAuthIdentity(String openid, CallbackState state) {}

    public RuntimeConfig runtime() {
        String origin = normalizeOrigin(configs.getConfigValue("wx.pay.official-return-origin", ""));
        return new RuntimeConfig(
                parseBoolean(configs.getConfigValue("wx.pay.official-enabled", "false")),
                trim(configs.getConfigValue("wx.pay.official-appid", "")),
                trim(configs.getConfigValue("wx.pay.official-appsecret", "")),
                origin);
    }

    public String authorizationUrl(String paymentNo, Long userId, String kind, String returnPath) {
        RuntimeConfig config = runtime();
        if (!config.ready()) throw new BusinessException(503, "微信公众号支付尚未配置完整");
        if (blank(paymentNo) || userId == null || blank(kind) || !returnPath.startsWith("/pages")) {
            throw new BusinessException(400, "公众号支付请求参数无效");
        }
        try {
            String state = HexFormat.of().formatHex(randomBytes(32));
            ObjectNode payload = json.createObjectNode();
            payload.put("paymentNo", paymentNo);
            payload.put("userId", userId);
            payload.put("kind", kind);
            payload.put("returnPath", returnPath);
            payload.put("returnOrigin", config.returnOrigin());
            redis.opsForValue().set(STATE_PREFIX + state, json.writeValueAsString(payload), STATE_TTL);
            String redirect = config.returnOrigin() + "/api/pay/wx/official/callback";
            return "https://open.weixin.qq.com/connect/oauth2/authorize?appid="
                    + encode(config.appId()) + "&redirect_uri=" + encode(redirect)
                    + "&response_type=code&scope=snsapi_base&state=" + state + "#wechat_redirect";
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.error("生成公众号支付授权地址失败", error);
            throw new BusinessException(503, "生成微信公众号支付授权地址失败");
        }
    }

    public OAuthIdentity exchange(String code, String state) {
        RuntimeConfig config = runtime();
        if (!config.ready()) throw new BusinessException(503, "微信公众号支付尚未配置完整");
        if (code == null || code.isBlank() || code.length() > 512 || state == null || !state.matches("[a-f0-9]{64}")) {
            throw new BusinessException(400, "公众号支付授权凭据无效");
        }
        String key = STATE_PREFIX + state;
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) throw new BusinessException(400, "公众号支付授权已过期，请返回订单重新支付");
        try {
            JsonNode payload = json.readTree(raw);
            String paymentNo = requiredText(payload, "paymentNo", 64);
            String kind = requiredText(payload, "kind", 32);
            String returnPath = requiredText(payload, "returnPath", 300);
            String returnOrigin = requiredText(payload, "returnOrigin", 300);
            long userId = payload.path("userId").asLong(0L);
            if (userId <= 0 || !returnOrigin.equals(config.returnOrigin()) || !returnPath.startsWith("/pages")) {
                throw new BusinessException(400, "公众号支付授权状态不一致");
            }
            URI endpoint = URI.create("https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + encode(config.appId())
                    + "&secret=" + encode(config.appSecret()) + "&code=" + encode(code) + "&grant_type=authorization_code");
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 65536) throw new BusinessException(502, "微信公众号授权服务暂不可用");
            JsonNode token = json.readTree(response.body());
            if (token.path("errcode").asInt(0) != 0) throw new BusinessException(502, "微信公众号网页授权失败，请确认 AppID、AppSecret 和网页授权域名");
            String openid = token.path("openid").asText("");
            if (!openid.matches("[A-Za-z0-9_-]{10,128}")) throw new BusinessException(502, "微信公众号返回的 OpenID 无效");
            return new OAuthIdentity(openid, new CallbackState(paymentNo, userId, kind, returnPath, returnOrigin));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.error("微信公众号网页授权失败: {}", error.getClass().getSimpleName());
            throw new BusinessException(502, "微信公众号网页授权失败，请重新发起支付");
        }
    }

    public String paymentPage(String appId, Map<String, String> params, String returnUrl) {
        try {
            Map<String, String> bridge = new LinkedHashMap<>();
            bridge.put("appId", appId);
            bridge.put("timeStamp", params.get("timeStamp"));
            bridge.put("nonceStr", params.get("nonceStr"));
            bridge.put("package", params.get("package"));
            bridge.put("signType", params.getOrDefault("signType", "RSA"));
            bridge.put("paySign", params.get("paySign"));
            String paramsJson = json.writeValueAsString(bridge);
            String resultJson = json.writeValueAsString(returnUrl);
            return page("正在调起微信支付", """
                    <script>
                    const payment = %s;
                    const returnUrl = %s;
                    function finish(status) {
                      const sep = returnUrl.includes('?') ? '&' : '?';
                      window.location.replace(returnUrl + sep + 'wechatPay=' + encodeURIComponent(status));
                    }
                    function invoke() {
                      if (typeof WeixinJSBridge === 'undefined') {
                        document.getElementById('tip').textContent = '请从微信内打开本页面完成公众号支付';
                        document.getElementById('retry').style.display = 'block';
                        return;
                      }
                      WeixinJSBridge.invoke('getBrandWCPayRequest', payment, function (res) {
                        if (res.err_msg === 'get_brand_wcpay_request:ok') finish('success');
                        else if (res.err_msg === 'get_brand_wcpay_request:cancel') finish('cancelled');
                        else finish('failed');
                      });
                    }
                    if (typeof WeixinJSBridge === 'undefined') {
                      document.addEventListener('WeixinJSBridgeReady', invoke, false);
                      setTimeout(function () {
                        if (typeof WeixinJSBridge === 'undefined') document.getElementById('tip').textContent = '微信支付组件加载失败，请从微信内重新打开订单';
                      }, 3000);
                    } else invoke();
                    </script>
                    """.formatted(paramsJson, resultJson));
        } catch (Exception error) {
            throw new BusinessException(500, "生成公众号支付页面失败");
        }
    }

    public String errorPage(String message, String returnUrl) {
        try {
            String safeMessage = message == null ? "支付未完成" : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String link = returnUrl == null || returnUrl.isBlank() ? "" : "<a class=\"button\" href=\"" + returnUrl.replace("&", "&amp;").replace("\"", "&quot;") + "\">返回订单</a>";
            return page("支付未完成", "<p id=\"tip\">" + safeMessage + "</p><p class=\"muted\">您可以关闭本页，回到原订单重新支付。</p>" + link);
        } catch (Exception error) {
            return "<!doctype html><meta charset=\"utf-8\"><p>支付未完成，请返回订单重试。</p>";
        }
    }

    private String page(String title, String content) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>body{margin:0;background:#f6f7f9;color:#17191c;font:16px -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}main{max-width:520px;margin:18vh auto;padding:28px;background:#fff;border:1px solid #e5e7eb;border-radius:16px;text-align:center}h1{font-size:22px}p{line-height:1.7}.muted{color:#73777e;font-size:14px}.button{display:inline-block;margin-top:16px;padding:12px 24px;border-radius:999px;background:#17191c;color:#fff;text-decoration:none}button{display:none;margin-top:16px;padding:12px 24px;border:0;border-radius:999px;background:#17191c;color:#fff}</style></head>"
                + "<body><main><h1>" + title + "</h1><p id=\"tip\">请稍候，正在打开微信支付…</p><button id=\"retry\" onclick=\"invoke()\">重新调起支付</button>" + content + "</main></body></html>";
    }

    public static String normalizeOrigin(String value) {
        String raw = trim(value);
        if (raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority();
        } catch (Exception error) {
            throw new BusinessException(400, "公众号支付站点 Origin 必须是不含路径的 HTTPS 地址");
        }
    }

    private static String requiredText(JsonNode node, String field, int max) {
        String value = node.path(field).asText("");
        if (value.isBlank() || value.length() > max) throw new BusinessException(400, "公众号支付授权状态无效");
        return value;
    }

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static boolean parseBoolean(String value) { return "true".equalsIgnoreCase(trim(value)); }
}
