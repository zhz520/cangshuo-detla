package com.delta.player.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One source of truth for the name printed on a player's payout account. */
@Service
@RequiredArgsConstructor
public class PlayerIdentityNameService {
    private final JdbcTemplate jdbc;
    private final SysConfigService config;

    public record IdentityName(String name, boolean approved, String source) {}

    public boolean required() {
        return Boolean.parseBoolean(config.getConfigValue("withdraw.require_identity_match", "true"));
    }

    public IdentityName resolve(Long playerId) {
        if (playerId == null) throw new BusinessException(404, "打手不存在");
        List<Map<String, Object>> verifications = jdbc.queryForList(
                "SELECT iv.status,iv.real_name,iv.reviewed_at FROM identity_verification iv " +
                        "JOIN `user` u ON u.id=iv.user_id JOIN player p ON p.openid=u.openid " +
                        "WHERE p.id=? AND p.deleted=0 AND u.deleted=0 " +
                        "ORDER BY iv.reviewed_at DESC, iv.user_id DESC", playerId);
        if (!verifications.isEmpty()) {
            for (Map<String, Object> row : verifications) {
                String name = text(row.get("real_name"));
                if ("APPROVED".equalsIgnoreCase(text(row.get("status"))) && !name.isBlank()) {
                    return new IdentityName(name, true, "IDENTITY_VERIFICATION");
                }
            }
            throw new BusinessException(409, "实名认证尚未通过，请先完成实名认证后再操作");
        }
        List<String> onboarding = jdbc.queryForList(
                "SELECT real_name FROM player WHERE id=? AND deleted=0", String.class, playerId);
        String name = onboarding.isEmpty() ? "" : text(onboarding.get(0));
        if (name.isBlank()) throw new BusinessException(409, "请先完善真实姓名后再操作");
        return new IdentityName(name, false, "ONBOARDING");
    }

    public IdentityName requireMatches(Long playerId, String accountName, String action) {
        IdentityName expected = resolve(playerId);
        if (!matches(expected.name(), accountName)) {
            throw new BusinessException(409, action + "收款姓名必须与实名姓名一致");
        }
        return expected;
    }

    public static boolean matches(String expected, String actual) {
        return !normalize(expected).isBlank() && normalize(expected).equals(normalize(actual));
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return normalized.replaceAll("[\\s\\p{Z}·•.。]", "").toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
