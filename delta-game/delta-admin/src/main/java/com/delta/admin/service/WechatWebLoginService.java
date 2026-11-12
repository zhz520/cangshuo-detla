package com.delta.admin.service;

import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.TokenService;
import com.delta.system.service.SysConfigService;
import com.delta.user.dto.LoginResponse;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Official-account web login with one-time state and ticket tokens. */
@Service
@RequiredArgsConstructor
public class WechatWebLoginService {
    private static final String STATE_PREFIX = "wechat:web-login:state:";
    private static final String TICKET_PREFIX = "wechat:web-login:ticket:";
    private final OfficialWechatService officialWechat;
    private final SysConfigService configs;
    private final RedisService redis;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final TokenService tokens;

    public String startUrl(String returnTo, HttpServletRequest request) {
        if (!"true".equalsIgnoreCase(configs.getConfigValue("auth.wechat.enabled", "false")))
            throw new BusinessException(403, "网页微信登录尚未启用");
        String appId = configs.getConfigValue("wechat.official.app_id", "").trim();
        String secret = configs.getConfigValue("wechat.official.app_secret", "").trim();
        if (appId.isBlank() || secret.isBlank()) throw new BusinessException(503, "公众号登录尚未配置");
        String safeReturn = safeReturn(returnTo);
        String state = UUID.randomUUID().toString().replace("-", "");
        redis.set(STATE_PREFIX + state, safeReturn, 5, TimeUnit.MINUTES);
        String redirect = callbackUrl(request);
        return "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + encode(appId)
                + "&redirect_uri=" + encode(redirect) + "&response_type=code&scope=snsapi_base&state=" + state + "#wechat_redirect";
    }

    public String complete(String code, String state, HttpServletRequest request) throws Exception {
        if (state == null || !state.matches("[a-f0-9]{32}") || code == null || code.isBlank() || code.length() > 256)
            throw new BusinessException(400, "微信登录凭据无效");
        Object stored = redis.getAndDelete(STATE_PREFIX + state);
        if (stored == null) throw new BusinessException(400, "微信登录已过期，请重新发起");
        String openid = officialWechat.exchangeOpenid(code);
        User user = findOrCreate(openid);
        if (!Integer.valueOf(1).equals(user.getStatus())) throw new BusinessException(403, "账号暂不可用");
        String ticket = UUID.randomUUID().toString().replace("-", "");
        redis.set(TICKET_PREFIX + ticket, String.valueOf(user.getId()), 2, TimeUnit.MINUTES);
        String target = String.valueOf(stored);
        return h5Origin() + target + (target.contains("?") ? "&" : "?") + "wechat_ticket=" + ticket;
    }

    public LoginResponse exchange(String ticket) {
        if (ticket == null || !ticket.matches("[a-f0-9]{32}")) throw new BusinessException(400, "登录凭据无效");
        Object value = redis.getAndDelete(TICKET_PREFIX + ticket);
        if (value == null) throw new BusinessException(400, "登录凭据已过期，请重新登录");
        long userId;
        try { userId = Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException error) { throw new BusinessException(400, "登录凭据无效"); }
        User user = users.getById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) throw new BusinessException(403, "账号暂不可用");
        LoginResponse response = new LoginResponse();
        response.setToken(tokens.createToken(user.getId(), UserTypeEnum.USER));
        response.setUserId(user.getId());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setPasswordSetupRequired(false);
        response.setPhoneBound(user.getPhone() != null && !user.getPhone().isBlank());
        return response;
    }

    private User findOrCreate(String openid) throws Exception {
        var rows = jdbc.queryForList("SELECT user_id FROM wechat_official_binding WHERE user_type='USER' AND openid=?", openid);
        if (!rows.isEmpty()) return users.getById(((Number) rows.get(0).get("user_id")).longValue());
        String synthetic = "oa-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((configs.getConfigValue("wechat.official.app_id", "") + ":" + openid).getBytes(StandardCharsets.UTF_8))).substring(0, 52);
        User user = users.loginOrRegister(synthetic, "", "");
        try {
            jdbc.update("INSERT INTO wechat_official_binding(user_type,user_id,openid) VALUES ('USER',?,?)", user.getId(), openid);
        } catch (DuplicateKeyException race) {
            var existing = jdbc.queryForList("SELECT user_id FROM wechat_official_binding WHERE user_type='USER' AND openid=?", openid);
            if (existing.isEmpty()) throw race;
            return users.getById(((Number) existing.get(0).get("user_id")).longValue());
        }
        return user;
    }

    private String callbackUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) scheme = request.getScheme();
        scheme = scheme.toLowerCase();
        if (!"https".equals(scheme) && !"http".equals(scheme)) throw new BusinessException(400, "回调协议无效");
        int port = request.getServerPort();
        String portPart = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80) ? "" : ":" + port;
        return scheme + "://" + request.getServerName() + portPart + "/user/auth/wechat/callback";
    }

    private String h5Origin() {
        String value = configs.getConfigValue("site.customer_web_url", "").trim();
        try {
            var uri = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
                throw new IllegalArgumentException();
            return "https://" + uri.getRawAuthority();
        } catch (Exception error) {
            throw new BusinessException(503, "请先配置客户 H5 站点地址");
        }
    }

    private String safeReturn(String value) {
        String path = value == null || value.isBlank() ? "/pages/login/index" : value.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\r") || path.contains("\n") || path.length() > 512)
            throw new BusinessException(400, "登录回跳地址无效");
        return path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
