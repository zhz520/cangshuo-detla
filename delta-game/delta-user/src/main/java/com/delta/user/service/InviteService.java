package com.delta.user.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;

/** User invite-code generation and first-order attribution rules. */
@Service
@RequiredArgsConstructor
public class InviteService {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public Map<String, Object> info(Long userId) {
        String code = codeFor(userId);
        Integer invited = jdbc.queryForObject("SELECT COUNT(*) FROM invite_relation WHERE inviter_id=?", Integer.class, userId);
        BigDecimal rewardTotal = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM invite_reward WHERE inviter_id=?", BigDecimal.class, userId);
        return Map.of("code", code, "invitedCount", invited == null ? 0 : invited,
                "rewardTotal", rewardTotal == null ? BigDecimal.ZERO : rewardTotal,
                "relations", jdbc.queryForList("SELECT status,created_at FROM invite_relation WHERE inviter_id=? ORDER BY id DESC LIMIT 50", userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void bind(Long userId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (!code.matches("[A-Z2-9]{8}")) throw new BusinessException(400, "邀请码格式不正确");
        var owners = jdbc.queryForList("SELECT user_id FROM invite_code WHERE code=?", code);
        if (owners.isEmpty()) throw new BusinessException(404, "邀请码不存在");
        Long inviter = ((Number) owners.get(0).get("user_id")).longValue();
        if (inviter.equals(userId)) throw new BusinessException(409, "不能邀请自己");
        Integer priorOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id=? AND status NOT IN ('UNPAID','CANCELLED')",
                Integer.class, userId);
        if (priorOrders != null && priorOrders > 0) throw new BusinessException(409, "邀请关系只能在首单完成前绑定");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM invite_relation WHERE invitee_id=?", Integer.class, userId) > 0)
            throw new BusinessException(409, "当前账号已归因，不能重复邀请");
        jdbc.update("INSERT INTO invite_relation(inviter_id,invitee_id,code,status) VALUES(?,?,?,'PENDING')", inviter, userId, code);
    }

    private String codeFor(Long userId) {
        var rows = jdbc.queryForList("SELECT code FROM invite_code WHERE user_id=?", userId);
        if (!rows.isEmpty()) return String.valueOf(rows.get(0).get("code"));
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder value = new StringBuilder(8);
            for (int i = 0; i < 8; i++) value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            try {
                jdbc.update("INSERT INTO invite_code(user_id,code) VALUES(?,?)", userId, value.toString());
                return value.toString();
            } catch (DuplicateKeyException ignored) {}
        }
        throw new BusinessException(503, "邀请码生成失败，请稍后重试");
    }
}
