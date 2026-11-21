package com.delta.governance.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin queries and mutations for player-homepage review, invitations and shifts. */
@Service
@RequiredArgsConstructor
public class GovernanceAdminService {
    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> revisions(String taskId) {
        return jdbc.queryForList("SELECT r.*,p.nickname FROM player_homepage_revision r LEFT JOIN player p ON p.id=r.player_id WHERE (? IS NULL OR r.id=?) ORDER BY r.created_at DESC LIMIT 300", taskId, taskId);
    }

    public List<Map<String, Object>> invitations() {
        return jdbc.queryForList("SELECT i.*,(SELECT COUNT(*) FROM player_enrollment e WHERE e.invite_token=i.token) AS registrations FROM player_invitation i ORDER BY i.created_at DESC LIMIT 300");
    }

    public String createInvite(String name, boolean oneDevice, LocalDateTime expiresAt, Long referrerId) {
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) throw new BusinessException(400, "有效期必须在未来");
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO player_invitation(token,name,referrer_id,one_device,expires_at) VALUES (?,?,?,?,?)",
                token, name, referrerId, oneDevice ? 1 : 0, expiresAt);
        return token;
    }

    public void toggle(String token, boolean enabled) {
        if (jdbc.update("UPDATE player_invitation SET enabled=? WHERE token=?", enabled ? 1 : 0, token) == 0)
            throw new BusinessException(404, "邀请链接不存在");
    }

    public List<Map<String, Object>> shifts() {
        return jdbc.queryForList("SELECT a.id,a.nickname,a.role,COALESCE(s.online,0) AS online FROM admin a LEFT JOIN staff_shift s ON s.admin_id=a.id WHERE a.deleted=0 AND a.status=1");
    }

    @Transactional(rollbackFor = Exception.class)
    public void shift(Long id, boolean online) {
        jdbc.queryForList("SELECT id FROM admin WHERE id=? FOR UPDATE", id);
        jdbc.update("DELETE FROM staff_shift WHERE admin_id=?", id);
        jdbc.update("INSERT INTO staff_shift(admin_id,online) VALUES (?,?)", id, online ? 1 : 0);
    }
}
