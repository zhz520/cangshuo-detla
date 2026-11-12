package com.delta.admin.controller;

import com.delta.common.domain.R;
import com.delta.common.annotation.OpLog;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations view for referral attribution, rewards and recoverable debts. */
@RestController
@RequestMapping("/admin/invites")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class InviteAdminController {
    private final JdbcTemplate jdbc;

    @GetMapping
    public R<Map<String, Object>> list() {
        List<Map<String, Object>> relations = jdbc.queryForList(
                "SELECT r.id,r.status,r.created_at,i.nickname inviter_nickname,e.nickname invitee_nickname " +
                        "FROM invite_relation r JOIN `user` i ON i.id=r.inviter_id JOIN `user` e ON e.id=r.invitee_id " +
                        "ORDER BY r.id DESC LIMIT 500");
        List<Map<String, Object>> rewards = jdbc.queryForList(
                "SELECT r.id,r.order_id,r.amount,r.status,r.created_at,u.nickname inviter_nickname " +
                        "FROM invite_reward r JOIN `user` u ON u.id=r.inviter_id ORDER BY r.id DESC LIMIT 500");
        List<Map<String, Object>> debts = jdbc.queryForList(
                "SELECT d.id,d.reward_id,d.amount,d.status,d.created_at,u.nickname inviter_nickname " +
                        "FROM invite_reward_debt d JOIN `user` u ON u.id=d.inviter_id ORDER BY d.id DESC LIMIT 500");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relations", relations);
        result.put("rewards", rewards);
        result.put("debts", debts);
        return R.ok(result);
    }

    @PutMapping("/debts/{id}/recovered")
    @OpLog(module = "marketing", operation = "标记邀请奖励欠款已追回")
    public R<Void> markDebtRecovered(@PathVariable Long id) {
        int updated = jdbc.update("UPDATE invite_reward_debt SET status='RECOVERED',recovered_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", id);
        if (updated == 1) return R.ok();
        var rows = jdbc.queryForList("SELECT status FROM invite_reward_debt WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "待追回记录不存在");
        if ("RECOVERED".equals(String.valueOf(rows.get(0).get("status")))) return R.ok();
        throw new BusinessException(409, "当前欠款状态不能标记为已追回");
    }
}
