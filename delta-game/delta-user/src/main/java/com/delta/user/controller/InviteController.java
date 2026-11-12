package com.delta.user.controller;

import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user/invites")
@RequiredArgsConstructor
public class InviteController {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    @GetMapping
    public R<Map<String, Object>> info() {
        Long userId = SecurityUtils.getUserId();
        String code = codeFor(userId);
        Integer invited = jdbc.queryForObject("SELECT COUNT(*) FROM invite_relation WHERE inviter_id=?", Integer.class, userId);
        java.math.BigDecimal rewardTotal = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM invite_reward WHERE inviter_id=?", java.math.BigDecimal.class, userId);
        return R.ok(Map.of("code", code, "invitedCount", invited == null ? 0 : invited,
                "rewardTotal", rewardTotal == null ? java.math.BigDecimal.ZERO : rewardTotal,
                "relations", jdbc.queryForList("SELECT status,created_at FROM invite_relation WHERE inviter_id=? ORDER BY id DESC LIMIT 50", userId)));
    }

    @PostMapping("/bind")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> bind(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getUserId();
        bindCode(userId, body == null ? "" : body.get("code"));
        return R.ok();
    }

    public void bindCode(Long userId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (!code.matches("[A-Z2-9]{8}")) throw new BusinessException(400, "邀请码格式不正确");
        var owners = jdbc.queryForList("SELECT user_id FROM invite_code WHERE code=?", code);
        if (owners.isEmpty()) throw new BusinessException(404, "邀请码不存在");
        Long inviter = ((Number) owners.get(0).get("user_id")).longValue();
        if (inviter.equals(userId)) throw new BusinessException(409, "不能邀请自己");
        Integer priorOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id=? AND status NOT IN ('UNPAID','CANCELLED')",
                Integer.class, userId);
        if (priorOrders != null && priorOrders > 0)
            throw new BusinessException(409, "邀请关系只能在首单完成前绑定");
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
            } catch (org.springframework.dao.DuplicateKeyException ignored) {}
        }
        throw new BusinessException(503, "邀请码生成失败，请稍后重试");
    }
}
