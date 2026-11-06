package com.delta.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.message.entity.SystemNotification;
import com.delta.message.mapper.SystemNotificationMapper;
import com.delta.message.service.SystemNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SystemNotificationServiceImpl extends ServiceImpl<SystemNotificationMapper, SystemNotification> implements SystemNotificationService {
    private final JdbcTemplate jdbc;

    @Override
    public void send(String receiverType, Long receiverId, String title, String content, String bizType, Long relatedId) {
        SystemNotification n = new SystemNotification();
        n.setReceiverType(receiverType);
        n.setReceiverId(receiverId != null ? receiverId : 0L);
        n.setTitle(title != null ? title : "");
        n.setContent(content);
        n.setBizType(bizType);
        n.setRelatedId(relatedId);
        n.setIsRead(0);
        n.setCreatedAt(LocalDateTime.now());
        save(n);
    }

    @Override
    public Page<SystemNotification> inbox(String type, Long id, long page, long size, String businessType) {
        var query = new LambdaQueryWrapper<SystemNotification>().eq(SystemNotification::getReceiverType, type)
                .eq(businessType != null && !businessType.isBlank(), SystemNotification::getBizType, businessType)
                .orderByDesc(SystemNotification::getCreatedAt, SystemNotification::getId);
        if ("CS".equals(type)) query.in(SystemNotification::getReceiverId, id, 0L);
        else query.eq(SystemNotification::getReceiverId, id);
        var result = page(new Page<>(Math.max(1, page), Math.max(1, Math.min(100, size))), query);
        if ("CS".equals(type)) {
            var broadcast = result.getRecords().stream().filter(n -> n.getReceiverId() == 0L && n.getIsRead() == 0).toList();
            if (!broadcast.isEmpty()) {
                var params = new ArrayList<Object>();
                params.add(id);
                broadcast.forEach(n -> params.add(n.getId()));
                var seen = new HashSet<>(jdbc.queryForList("SELECT notification_id FROM staff_notification_receipt WHERE receiver_id=? "
                        + "AND notification_id IN (" + String.join(",", java.util.Collections.nCopies(broadcast.size(), "?")) + ")",
                        Long.class, params.toArray()));
                broadcast.forEach(n -> { if (seen.contains(n.getId())) n.setIsRead(1); });
            }
        }
        return result;
    }

    @Override
    public int unreadCount(String type, Long id) {
        if ("CS".equals(type)) {
            return jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_notification n WHERE n.receiver_type='CS' AND n.is_read=0
                AND (n.receiver_id=? OR (n.receiver_id=0 AND NOT EXISTS (
                    SELECT 1 FROM staff_notification_receipt r WHERE r.receiver_id=? AND r.notification_id=n.id)))
                """, Integer.class, id, id);
        }
        return Math.toIntExact(count(new LambdaQueryWrapper<SystemNotification>().eq(SystemNotification::getIsRead, 0)
                .eq(SystemNotification::getReceiverType, type).eq(SystemNotification::getReceiverId, id)));
    }

    @Override
    public boolean markRead(String type, Long id, Long notificationId) {
        var notice = getById(notificationId);
        if (notice == null || !type.equals(notice.getReceiverType())) return false;
        if ("CS".equals(type) && notice.getReceiverId() == 0L) {
            jdbc.update("""
                INSERT INTO staff_notification_receipt(receiver_id,notification_id) VALUES (?,?)
                ON DUPLICATE KEY UPDATE read_at=staff_notification_receipt.read_at
                """, id, notificationId);
        } else if (id.equals(notice.getReceiverId())) {
            update(ownedUnread(type, id).eq(SystemNotification::getId, notificationId));
        } else return false;
        return true;
    }

    @Override
    @Transactional
    public void markAllRead(String type, Long id) {
        update(ownedUnread(type, id));
        if ("CS".equals(type)) {
            jdbc.update("""
                INSERT INTO staff_notification_receipt(receiver_id,notification_id)
                SELECT ?,n.id FROM system_notification n WHERE n.receiver_type='CS' AND n.receiver_id=0 AND n.is_read=0
                AND NOT EXISTS (SELECT 1 FROM staff_notification_receipt r WHERE r.receiver_id=? AND r.notification_id=n.id)
                ON DUPLICATE KEY UPDATE read_at=staff_notification_receipt.read_at
                """, id, id);
        }
    }

    private LambdaUpdateWrapper<SystemNotification> ownedUnread(String type, Long id) {
        return new LambdaUpdateWrapper<SystemNotification>()
                .eq(SystemNotification::getReceiverType, type)
                .eq(SystemNotification::getReceiverId, id)
                .eq(SystemNotification::getIsRead, 0)
                .set(SystemNotification::getIsRead, 1);
    }
}
