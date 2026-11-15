package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.domain.R;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.security.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import com.delta.message.service.SystemNotificationService;
import com.delta.order.entity.OrderRelayRequest;
import com.delta.order.entity.PlayerReplaceRequest;
import com.delta.order.service.OrderRelayRequestService;
import com.delta.order.service.PlayerReplaceRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 客服端提醒红点：投诉未读、接力待审核、消息未读、系统通知未读 */
@RestController
@RequestMapping("/cs/remind")
@RequiredArgsConstructor
public class CsRemindController {
    private final SystemNotificationService systemNotificationService;
    private final com.delta.common.mapper.CrossModuleMapper crossModuleMapper;
    private final OrderRelayRequestService relayRequestService;
    private final PlayerReplaceRequestService replaceRequestService;
    private final JdbcTemplate jdbc;

    @GetMapping
    public R<Map<String, Object>> remind() {
        Long adminId = SecurityUtils.getUserId();
        long csEncodedId = ChatParticipantId.encodeCs(adminId);

        Long raw = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complaint c JOIN `order` o ON o.id=c.order_id " +
                        "WHERE c.status IN ('PENDING','PROCESSING') AND c.cs_read_at IS NULL " +
                        "AND (o.cs_id=? OR o.cs_id IS NULL)",
                Long.class, adminId);
        long complaintUnread = raw != null ? raw : 0L;
        long relayUnread = relayRequestService.count(new LambdaQueryWrapper<OrderRelayRequest>()
                .apply("order_id IN (SELECT id FROM `order` WHERE cs_id = {0} OR cs_id IS NULL)", adminId)
                .eq(OrderRelayRequest::getStatus, "PENDING"));
        long replaceUnread = replaceRequestService.count(new LambdaQueryWrapper<PlayerReplaceRequest>()
                .apply("order_id IN (SELECT id FROM `order` WHERE cs_id = {0} OR cs_id IS NULL)", adminId)
                .eq(PlayerReplaceRequest::getStatus, "PENDING"));
        int messageUnread = crossModuleMapper.selectTotalChatUnread(csEncodedId, "CS");
        int systemUnread = systemNotificationService.unreadCount("CS", adminId);

        Map<String, Object> map = new HashMap<>();
        map.put("complaintUnread", complaintUnread);
        map.put("relayUnread", relayUnread);
        map.put("replaceUnread", replaceUnread);
        map.put("messageUnread", messageUnread);
        map.put("systemUnread", systemUnread);
        return R.ok(map);
    }
}
