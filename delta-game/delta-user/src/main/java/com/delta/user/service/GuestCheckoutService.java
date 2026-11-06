package com.delta.user.service;

import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.RateLimitService;
import com.delta.common.security.service.TokenService;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.system.service.SysConfigService;
import com.delta.user.dto.GuestLookupRequest;
import com.delta.user.dto.GuestOrderCreateRequest;
import com.delta.user.dto.GuestOrderSession;
import com.delta.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GuestCheckoutService {
    private static final String PRIVACY_VERSION = "guest-checkout-v1";
    private final UserService users;
    private final WalletService wallets;
    private final OrderService orders;
    private final SysConfigService configs;
    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final RateLimitService limits;

    @Value("${identity.hash-pepper}")
    private String pepper;

    private record GuestProfile(long userId, String nickname, int status, int deleted, String maskedPhone, String lookupHash) {}

    @Transactional(rollbackFor = Exception.class)
    public GuestOrderSession create(GuestOrderCreateRequest request, String clientIp) {
        requireGuestCheckoutEnabled();
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            throw new BusinessException(400, "请阅读并同意用户协议和隐私政策");
        }
        String phone = request.phone().trim();
        String lookupCode = normalizeCode(request.lookupCode());
        String phoneHash = hash("GUEST_PHONE:" + phone);
        String lookupHash = hash("GUEST_LOOKUP:" + lookupCode);
        limits.check("guest-create-ip:" + safeIp(clientIp), 10, 3600);

        GuestProfile profile = findByPhone(phoneHash);
        if (profile != null && !lookupHash.equals(profile.lookupHash())) {
            limits.check("guest-create-fail-phone:" + phoneHash, 20, 3600);
            throw new BusinessException(409, "该手机号已有游客订单，请使用原查单码");
        }
        if (profile == null) {
            limits.check("guest-create-new-phone:" + phoneHash, 3, 86400);
            User guest = new User();
            String guestKey = UUID.randomUUID().toString().replace("-", "");
            guest.setOpenid("guest:" + guestKey);
            guest.setNickname("游客" + guestKey.substring(0, 4).toUpperCase(Locale.ROOT));
            guest.setAvatar("");
            guest.setPhone("");
            guest.setStatus(1);
            guest.setIsGuest(1);
            guest.setAuthVersion(0L);
            users.save(guest);
            wallets.initWallet(guest.getId());
            jdbc.update("INSERT INTO guest_checkout_profile(user_id,phone_hash,phone_mask,lookup_hash,privacy_version,privacy_accepted_at) " +
                            "VALUES(?,?,?,?,?,?)",
                    guest.getId(), phoneHash, maskPhone(phone), lookupHash, PRIVACY_VERSION, LocalDateTime.now());
            profile = new GuestProfile(guest.getId(), guest.getNickname(), 1, 0, maskPhone(phone), lookupHash);
        } else {
            requireUsable(profile);
        }

        if (request.order().getContact() == null || request.order().getContact().isBlank()) {
            request.order().setContact(phone);
        }
        Order order = orders.createOrder(profile.userId(), request.order());
        return session(profile, order.getId());
    }

    public GuestOrderSession lookup(GuestLookupRequest request, String clientIp) {
        String phone = request.phone().trim();
        String phoneHash = hash("GUEST_PHONE:" + phone);
        limits.check("guest-lookup-ip:" + safeIp(clientIp), 30, 3600);
        GuestProfile profile = findProfile(phoneHash, hash("GUEST_LOOKUP:" + normalizeCode(request.lookupCode())));
        if (profile == null) {
            limits.check("guest-lookup-fail-phone:" + phoneHash, 15, 3600);
            throw new BusinessException(404, "手机号或查单码不正确");
        }
        requireUsable(profile);
        return session(profile, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void bind(Long registeredUserId, GuestLookupRequest request) {
        limits.check("guest-bind:" + registeredUserId, 10, 3600);
        User registered = users.getById(registeredUserId);
        if (registered == null || Integer.valueOf(1).equals(registered.getIsGuest())
                || !Integer.valueOf(1).equals(registered.getStatus())) {
            throw new BusinessException(403, "请先登录正式账号");
        }
        String phone = request.phone().trim();
        if (!phone.equals(registered.getPhone())) {
            throw new BusinessException(403, "游客手机号必须与当前登录账号一致");
        }
        GuestProfile profile = findProfile(hash("GUEST_PHONE:" + phone),
                hash("GUEST_LOOKUP:" + normalizeCode(request.lookupCode())));
        if (profile == null) throw new BusinessException(404, "手机号或查单码不正确");
        jdbc.queryForList("SELECT user_id FROM guest_checkout_profile WHERE user_id=? FOR UPDATE", profile.userId());
        jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE", profile.userId());
        requireUsable(profile);
        if (profile.userId() == registeredUserId) throw new BusinessException(409, "游客账户与正式账户状态异常");

        Integer incomplete = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id=? AND status NOT IN (?,?,?,?,?)",
                Integer.class, profile.userId(),
                "CANCELLED", "REFUNDED", "CONFIRMED", "REVIEWED", "ARBITRATED");
        if (incomplete != null && incomplete > 0) {
            throw new BusinessException(409, "请先完成游客订单支付与履约，再绑定正式账号");
        }
        Integer unsettledPayments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE user_id=? AND status IN (?,?)",
                Integer.class, profile.userId(), "PAYING", "REFUNDING");
        if (unsettledPayments != null && unsettledPayments > 0) {
            throw new BusinessException(409, "游客订单仍有支付或退款处理中，请稍后再绑定");
        }
        Integer openComplaints = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complaint WHERE user_id=? AND status IN (?,?,?)",
                Integer.class, profile.userId(), "PENDING", "PROCESSING", "APPEALING");
        if (openComplaints != null && openComplaints > 0) {
            throw new BusinessException(409, "游客仍有未结束的售后工单，请处理后再绑定");
        }
        List<Long> linked = jdbc.queryForList(
                "SELECT registered_user_id FROM guest_user_link WHERE guest_user_id=? FOR UPDATE",
                Long.class, profile.userId());
        if (!linked.isEmpty()) {
            if (linked.get(0).equals(registeredUserId)) return;
            throw new BusinessException(409, "该游客账户已绑定其他正式账号");
        }
        jdbc.update("INSERT INTO guest_user_link(guest_user_id,registered_user_id) VALUES(?,?)",
                profile.userId(), registeredUserId);
        jdbc.update("UPDATE `user` SET auth_version=COALESCE(auth_version,0)+1 WHERE id=?", profile.userId());
    }

    private GuestOrderSession session(GuestProfile profile, Long orderId) {
        return new GuestOrderSession(tokens.createToken(profile.userId(), UserTypeEnum.GUEST),
                profile.userId(), profile.nickname(), profile.maskedPhone(), orderId);
    }

    private GuestProfile findProfile(String phoneHash, String lookupHash) {
        return jdbc.query("SELECT u.id,u.nickname,u.status,u.deleted,g.phone_mask,g.lookup_hash " +
                        "FROM guest_checkout_profile g JOIN `user` u ON u.id=g.user_id " +
                        "WHERE g.phone_hash=? AND g.lookup_hash=? LIMIT 1",
                (org.springframework.jdbc.core.ResultSetExtractor<GuestProfile>) rs -> {
                    if (!rs.next()) return null;
                    return new GuestProfile(rs.getLong("id"), rs.getString("nickname"),
                            rs.getInt("status"), rs.getInt("deleted"), rs.getString("phone_mask"),
                            rs.getString("lookup_hash"));
                },
                phoneHash, lookupHash);
    }

    private GuestProfile findByPhone(String phoneHash) {
        return jdbc.query("SELECT u.id,u.nickname,u.status,u.deleted,g.phone_mask,g.lookup_hash " +
                        "FROM guest_checkout_profile g JOIN `user` u ON u.id=g.user_id " +
                        "WHERE g.phone_hash=? LIMIT 1",
                (org.springframework.jdbc.core.ResultSetExtractor<GuestProfile>) rs -> {
                    if (!rs.next()) return null;
                    return new GuestProfile(rs.getLong("id"), rs.getString("nickname"),
                            rs.getInt("status"), rs.getInt("deleted"), rs.getString("phone_mask"),
                            rs.getString("lookup_hash"));
                },
                phoneHash);
    }

    private void requireUsable(GuestProfile profile) {
        if (profile.status() != 1 || profile.deleted() != 0) {
            throw new BusinessException(403, "游客账户已停用，请联系客服");
        }
    }

    private void requireGuestCheckoutEnabled() {
        if (!"true".equalsIgnoreCase(configs.getConfigValue("order.guest_checkout_enabled", "false"))) {
            throw new BusinessException(403, "游客下单暂未开放，请登录后下单");
        }
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9]{6,12}")) throw new BusinessException(400, "查单码须为6至12位数字或字母");
        return value;
    }

    private static String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String safeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.substring(0, Math.min(ip.length(), 64));
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("游客凭证摘要失败", error);
        }
    }
}
