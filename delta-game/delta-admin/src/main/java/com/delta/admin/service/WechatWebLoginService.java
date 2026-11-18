package com.delta.admin.service;

import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.VerificationService;
import com.delta.system.service.SysConfigService;
import com.delta.user.entity.User;
import com.delta.user.service.WebLoginPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class WechatWebLoginService {
    private static final String STATE = "wechat:web-login:state:";
    private static final String LOGIN = "wechat:web-login:v2:ticket:";
    private static final String PHONE = "wechat:web-login:phone:";
    private final OfficialWechatService officialWechat;
    private final SysConfigService configs;
    private final WebLoginPolicy policy;
    private final RedisService redis;
    private final WechatIdentityService identity;
    private final VerificationService verification;

    public record Authorization(String url, String state) {}
    public record Completion(String token, Long userId, String nickname, String avatar,
                             boolean phoneBound, String bindTicket, String confirmationTicket) {}
    public record SecurityBinding(String bindTicket, String maskedPhone) {}
    public record SecurityStatus(String phone, boolean phoneBound, boolean wechatBound,
                                 boolean wechatAvailable, boolean passwordSet, boolean passwordLoginEnabled) {}

    public Authorization start(String returnTo, String inviteCode, Long accountId) {
        requireWechat();
        String target = accountId != null ? "/pages/mine/security" : safeReturn(returnTo);
        String invite = accountId == null ? safeInvite(inviteCode) : "";
        String appId = appId();
        String state = nonce();
        Map<String,String> claim = new HashMap<>(Map.of("returnTo", target, "invite", invite, "appId", appId));
        if (accountId != null) {
            User user = identity.requireAccount(accountId);
            claim.put("account", accountId.toString());
            claim.put("version", version(user));
        }
        redis.set(STATE + state, claim, 5, TimeUnit.MINUTES);
        String redirect = origin() + "/api/user/auth/wechat/callback";
        return new Authorization("https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + encode(appId)
                + "&redirect_uri=" + encode(redirect) + "&response_type=code&scope=snsapi_base&state=" + state + "#wechat_redirect", state);
    }

    public String complete(String code, String state, String browserState) throws Exception {
        if (!sameBrowser(state, browserState))
            throw new BusinessException(400, "微信授权未完成，请重新登录");
        var claim = checked(redis.getAndDelete(STATE + state));
        if (code == null || code.isBlank() || code.length() > 256)
            return failureRedirect(claim);
        String openid;
        try {
            openid = officialWechat.exchangeOpenid(code);
            checked(claim);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failureRedirect(claim);
        } catch (Exception ignored) {
            return failureRedirect(claim);
        }
        String target = claim.get("returnTo");
        claim.put("openid", openid);
        String ticket = nonce();
        redis.set(LOGIN + ticket, claim, 2, TimeUnit.MINUTES);
        return origin() + "/#" + target + (target.contains("?") ? "&" : "?") + "wechat_ticket=" + ticket;
    }

    public Completion exchange(String ticket) {
        var claim = checked(redis.getAndDelete(LOGIN + requireTicket(ticket)));
        if (claim.containsKey("account")) throw new BusinessException(401, "登录凭据无效");
        Completion authenticated = identity.signIfBound(claim.get("openid"));
        if (authenticated != null) return authenticated;
        User user = identity.findWechatAccount(claim.get("openid"));
        return phoneTicket(claim, user);
    }

    private Completion phoneTicket(Map<String,String> claim, User user) {
        claim.put("source", user == null ? "" : user.getId().toString());
        claim.put("sourceVersion", user == null ? "" : version(user));
        String bindTicket = nonce();
        redis.set(PHONE + bindTicket, claim, 10, TimeUnit.MINUTES);
        return new Completion(null, null, null, null, false, bindTicket, null);
    }

    public Map<String,Object> sendCode(String ticket, String phone, String captchaId, String captchaCode, String ip, Long accountId) {
        var claim = phoneClaim(PHONE + requireTicket(ticket), accountId);
        checkTargetPhone(claim, phone);
        return verification.send(phone, "WECHAT_BIND", ticket, captchaId, captchaCode, ip);
    }

    public Completion bindPhone(String ticket, String phone, String requestId, String code, Long accountId) {
        String key = PHONE + requireTicket(ticket);
        var claim = phoneClaim(key, accountId);
        checkTargetPhone(claim, phone);
        verification.verify(phone, "WECHAT_BIND", ticket, requestId, code);
        boolean takeover = claim.containsKey("target");
        User existing = takeover ? null : identity.findPhoneAccount(phone);
        if (takeover || existing != null) identity.checkTransferSource(claim);
        // A protected source can try a new phone with the same OAuth proof, but must verify a fresh SMS.
        claim = checked(redis.getAndDelete(key));
        if (takeover) return confirmationTicket(claim);
        if (existing == null) {
            try {
                return identity.bindNewPhone(claim, phone);
            } catch (PessimisticLockingFailureException conflict) {
                throw WechatIdentityService.changed();
            }
        }
        claim.put("phone", phone);
        claim.put("target", existing.getId().toString());
        claim.put("targetVersion", version(existing));
        claim.put("targetWechat", identity.accountWechat(existing.getId(), false));
        return confirmationTicket(claim);
    }

    private Completion confirmationTicket(Map<String,String> claim) {
        String confirm = nonce();
        redis.set(PHONE + "confirm:" + confirm, claim, 5, TimeUnit.MINUTES);
        return new Completion(null, null, null, null, false, null, confirm);
    }

    public Completion confirm(String ticket, Long accountId) {
        String key = PHONE + "confirm:" + requireTicket(ticket);
        phoneClaim(key, accountId);
        var claim = checked(redis.getAndDelete(key));
        try {
            return identity.merge(claim);
        } catch (PessimisticLockingFailureException conflict) {
            throw WechatIdentityService.changed();
        }
    }

    public SecurityBinding bindExisting(Long accountId, String ticket) {
        String key = LOGIN + requireTicket(ticket);
        if (!accountId.toString().equals(checked(redis.get(key)).get("account")))
            throw new BusinessException(401, "绑定凭据无效");
        var claim = checked(redis.getAndDelete(key));
        try {
            User source = identity.attachToExisting(accountId, claim.get("version"), claim.get("openid"));
            if (source == null) return null;
            if (!accountId.equals(source.getId())) {
                User target = identity.requireAccount(accountId);
                if (!hasPhone(target) || !Objects.equals(claim.get("version"), version(target)))
                    throw WechatIdentityService.changed();
                claim.put("phone", target.getPhone());
                claim.put("target", accountId.toString());
                claim.put("targetVersion", claim.get("version"));
                claim.put("targetWechat", ""); // attachToExisting established that the target was unbound.
            }
            return new SecurityBinding(phoneTicket(claim, source).bindTicket(), maskPhone(claim.get("phone")));
        } catch (PessimisticLockingFailureException conflict) {
            throw WechatIdentityService.changed();
        }
    }

    private static void checkTargetPhone(Map<String,String> claim, String phone) {
        if (claim.containsKey("target") && !Objects.equals(claim.get("phone"), phone))
            throw new BusinessException(400, "请输入当前账号已绑定的手机号");
    }

    private Map<String,String> phoneClaim(String key, Long accountId) {
        var claim = checked(redis.get(key));
        if (claim.containsKey("account") && !Objects.equals(claim.get("account"), Objects.toString(accountId, "")))
            throw new BusinessException(401, "请在发起绑定的账号下继续操作");
        return claim;
    }

    public SecurityStatus security(Long id) {
        User user = identity.requireAccount(id);
        return new SecurityStatus(maskPhone(user.getPhone()),
                hasPhone(user), identity.hasWechat(id), policy.wechatEnabled(), user.isPasswordSet(), policy.passwordEnabled());
    }

    private static String maskPhone(String phone) {
        return phone != null && phone.matches("^1\\d{10}$") ? phone.substring(0,3) + "****" + phone.substring(7) : "";
    }

    public String failureRedirect() {
        return failureRedirect(Map.of("returnTo", "/pages/login/index"));
    }

    private String failureRedirect(Map<String,String> claim) {
        String path = claim.get("returnTo");
        return origin() + "/#" + path + (path.contains("?") ? "&" : "?") + "wechat_error=1";
    }

    private static boolean sameBrowser(String state, String browserState) {
        return valid(state) && valid(browserState)
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII), browserState.getBytes(StandardCharsets.US_ASCII));
    }

    @SuppressWarnings("unchecked")
    private HashMap<String,String> checked(Object value) {
        requireWechat();
        if (!(value instanceof Map<?,?> claim) || !appId().equals(claim.get("appId")))
            throw new BusinessException(401, "微信凭据已过期，请重新授权");
        return new HashMap<>((Map<String,String>) claim);
    }

    private void requireWechat() {
        if (!policy.wechatEnabled()) throw new BusinessException(403, "微信登录暂不可用，请使用手机号登录");
    }

    private String appId() {
        return configs.getConfigValue("wechat.official.app_id", "").trim();
    }

    private String origin() {
        String origin = policy.customerOrigin();
        if (origin == null) throw new BusinessException(503, "请先配置客户 H5 站点地址");
        return origin;
    }

    static String version(User user) { return String.valueOf(user.getAuthVersion() == null ? 0 : user.getAuthVersion()); }
    static boolean hasPhone(User user) { return user.getPhone() != null && user.getPhone().matches("^1\\d{10}$"); }

    private static boolean valid(String value) { return value != null && value.matches("[a-f0-9]{32}"); }
    private static String requireTicket(String value) {
        if (!valid(value)) throw new BusinessException(401, "微信凭据无效，请重新授权");
        return value;
    }
    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String safeReturn(String value) {
        String path = value == null || value.isBlank() ? "/pages/login/index" : value.trim();
        if (path.length() > 512 || !path.matches("^/pages/login/index(?:\\?[^#\\\\\\r\\n]*)?$"))
            throw new BusinessException(400, "登录回跳地址无效");
        return path;
    }
    private static String safeInvite(String value) {
        String invite = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!invite.isEmpty() && !invite.matches("[A-Z2-9]{8}")) throw new BusinessException(400, "邀请码格式不正确");
        return invite;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
