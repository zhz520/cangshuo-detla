package com.delta.admin.service;

import com.delta.common.exception.BusinessException;
import com.delta.message.service.SystemNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** User privacy requests, account deletion and identity-erasure review workflow. */
@Service
@RequiredArgsConstructor
public class PrivacyRequestService {
    private final JdbcTemplate jdbc;
    private final UserManagementService management;
    private final SystemNotificationService notifications;

    public record RequestView(String id, String kind, String status, String note,
                              @com.fasterxml.jackson.annotation.JsonProperty("review_note") String reviewNote,
                              @com.fasterxml.jackson.annotation.JsonProperty("created_at") LocalDateTime createdAt,
                              @com.fasterxml.jackson.annotation.JsonProperty("reviewed_at") LocalDateTime reviewedAt) {}

    public List<RequestView> mine(Long userId) {
        return jdbc.query("""
                SELECT id,kind,status,note,review_note,created_at,reviewed_at
                FROM privacy_request WHERE user_id=? ORDER BY created_at DESC LIMIT 30
                """, (row, index) -> new RequestView(row.getString("id"), row.getString("kind"), row.getString("status"),
                row.getString("note"), row.getString("review_note"), row.getObject("created_at", LocalDateTime.class),
                row.getObject("reviewed_at", LocalDateTime.class)), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void request(Long userId, String kind, String note) {
        jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE", userId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM privacy_request WHERE user_id=? AND status='PENDING'", Long.class, userId) > 0)
            throw new BusinessException(409, "已有待处理申请");
        jdbc.update("INSERT INTO privacy_request(id,user_id,kind,status,note) VALUES (?,?,?,'PENDING',?)",
                UUID.randomUUID().toString(), userId, kind, note);
    }

    public List<Map<String, Object>> list(String taskId) {
        return jdbc.queryForList("SELECT r.*,u.nickname FROM privacy_request r LEFT JOIN `user` u ON u.id=r.user_id WHERE (? IS NULL OR r.id=?) ORDER BY r.created_at DESC LIMIT 300", taskId, taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void review(String id, boolean approved, String note, Long reviewerId) {
        var requests = jdbc.queryForList("SELECT * FROM privacy_request WHERE id=? FOR UPDATE", id);
        if (requests.isEmpty() || !"PENDING".equals(requests.get(0).get("status"))) throw new BusinessException(409, "申请不存在或已处理");
        var row = requests.get(0);
        long userId = ((Number) row.get("user_id")).longValue();
        if (approved) {
            var users = jdbc.queryForList("SELECT openid FROM `user` WHERE id=? FOR UPDATE", userId);
            if (users.isEmpty()) throw new BusinessException(404, "账号不存在");
            String openid = (String) users.get(0).get("openid");
            jdbc.update("DELETE FROM identity_verification WHERE user_id=?", userId);
            if ("CLOSE_ACCOUNT".equals(row.get("kind"))) {
                management.delete(userId);
                if (jdbc.queryForObject("SELECT COUNT(*) FROM order_player op JOIN `order` o ON o.id=op.order_id JOIN player p ON p.id=op.player_id WHERE p.openid=? AND op.status='ACCEPTED' AND o.status NOT IN ('CONFIRMED','REVIEWED','SETTLED','CANCELLED','REFUNDED','ARBITRATED')", Long.class, openid) > 0)
                    throw new BusinessException(409, "仍有队友订单未完成，请先处理");
                jdbc.update("UPDATE `user` SET phone='',openid=?,nickname='已注销用户',avatar='',password=NULL,auth_version=COALESCE(auth_version,0)+1 WHERE id=?", "closed:" + userId, userId);
                jdbc.update("UPDATE player SET real_name='',phone='',nickname='已注销打手',avatar='',cover_image='',album_images='',story='',voice_url='',introduction='',proof_images='',status='FROZEN',is_online=0 WHERE openid=?", openid);
                jdbc.update("UPDATE player_homepage_revision SET payload='{}' WHERE player_id IN (SELECT id FROM player WHERE openid=?)", openid);
                jdbc.update("DELETE FROM wechat_official_binding WHERE user_type='USER' AND user_id=?", userId);
                jdbc.update("DELETE FROM wechat_official_binding WHERE user_type='PLAYER' AND user_id IN (SELECT id FROM player WHERE openid=?)", openid);
            } else jdbc.update("UPDATE player SET real_name='' WHERE openid=?", openid);
        }
        jdbc.update("UPDATE privacy_request SET status=?,review_note=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?", approved ? "APPROVED" : "REJECTED", note, reviewerId, id);
        if (!approved || !"CLOSE_ACCOUNT".equals(row.get("kind"))) {
            notifications.send("USER", userId, "隐私申请已处理", "请在隐私与注销页面查看审核结果。", "PRIVACY_REQUEST", null);
        }
    }
}
