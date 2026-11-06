package com.delta.message.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.delta.message.entity.SystemNotification;

public interface SystemNotificationService extends IService<SystemNotification> {
    void send(String receiverType, Long receiverId, String title, String content, String bizType, Long relatedId);
    Page<SystemNotification> inbox(String type, Long id, long page, long size, String businessType);
    int unreadCount(String receiverType, Long receiverId);
    boolean markRead(String type, Long id, Long notificationId);
    void markAllRead(String receiverType, Long receiverId);
}
