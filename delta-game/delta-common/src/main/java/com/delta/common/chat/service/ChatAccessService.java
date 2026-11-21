package com.delta.common.chat.service;

import com.delta.common.chat.domain.entity.ChatSession;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.mapper.CrossModuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天创建和订单会话查找所需的只读访问校验。
 */
@Service
@RequiredArgsConstructor
public class ChatAccessService {
    private final JdbcTemplate jdbcTemplate;
    private final CrossModuleMapper crossModuleMapper;

    public boolean canGuestContactPlayer(long guestUserId, long playerId) {
        Integer related = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id=? AND player_id=? AND status IN ('ASSIGNED','ACCEPTED','WAITING_TEAMMATE','IN_PROGRESS','COMPLETED','CONFIRMED','REVIEWED','DISPUTED','ARBITRATED')",
                Integer.class, guestUserId, playerId);
        return related != null && related > 0;
    }

    public boolean participantExists(int entityType, long rawId) {
        String table = switch (entityType) {
            case 1 -> "`user`";
            case 2 -> "player";
            case 3 -> "admin";
            default -> null;
        };
        if (table == null || rawId <= 0) return false;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id=? AND deleted=0", Long.class, rawId);
        return count != null && count > 0;
    }

    public OrderParticipants findOrderParticipants(long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, player_id FROM `order` WHERE id = ?", orderId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long userId = ((Number) row.get("user_id")).longValue();
        Number playerId = (Number) row.get("player_id");
        return new OrderParticipants(userId, playerId == null ? null : playerId.longValue());
    }

    public Long findRandomAvailableCsId() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM admin WHERE role IN ('CS','BOTH','ADMIN') AND status = 1 AND deleted = 0 ORDER BY RAND() LIMIT 1");
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    public void enrichSession(ChatSession session, long myEncodedId) {
        long targetId = (session.getId1() != null && session.getId1().equals(myEncodedId)) ? session.getId2() : session.getId1();
        session.setTargetId(targetId);
        session.setTargetName(crossModuleMapper.selectNicknameByEncodedId(targetId));
        session.setAvatar(crossModuleMapper.selectAvatarByEncodedId(targetId));
        session.setTargetType(ChatParticipantId.getEntityType(targetId) == 3 ? "CS" : ChatParticipantId.getEntityType(targetId) == 2 ? "PLAYER" : "USER");
        String lastType = crossModuleMapper.selectLastMessageType(session.getId());
        if (lastType != null) {
            switch (lastType) {
                case "IMAGE" -> session.setLastMessage("[图片]");
                case "PRODUCT" -> session.setLastMessage("[商品]");
                case "ORDER" -> session.setLastMessage("[订单]");
                default -> session.setLastMessage(crossModuleMapper.selectLastMessageContent(session.getId()));
            }
        }
        String excludeType = ChatParticipantId.getEntityType(myEncodedId) == 1 ? "USER" :
                ChatParticipantId.getEntityType(myEncodedId) == 2 ? "PLAYER" : "CS";
        session.setUnreadCount(crossModuleMapper.selectUnreadCount(session.getId(), excludeType));
    }

    public record OrderParticipants(long userId, Long playerId) {}
}
