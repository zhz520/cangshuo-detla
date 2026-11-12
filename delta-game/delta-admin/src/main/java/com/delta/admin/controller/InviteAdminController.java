package com.delta.admin.controller;

import com.delta.common.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only operations view for referral attribution, rewards and unrecovered debts. */
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
}
