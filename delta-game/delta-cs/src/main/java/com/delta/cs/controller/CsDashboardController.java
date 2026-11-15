package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.domain.R;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.service.ComplaintService;
import com.delta.order.service.OrderService;
import com.delta.player.entity.Player;
import com.delta.player.service.PlayerService;
import com.delta.common.mapper.StatsMapper;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服工作台Dashboard
 */
@RestController
@RequestMapping("/cs/dashboard")
@RequiredArgsConstructor
public class CsDashboardController {
    private final OrderService orderService;
    private final ComplaintService complaintService;
    private final PlayerService playerService;
    private final StatsMapper statsMapper;
    private final JdbcTemplate jdbc;
    private final com.delta.common.security.service.StaffPermissions staffPermissions;

    @GetMapping
    public R<Map<String, Object>> dashboard() {
        Map<String, Object> result = new HashMap<>();
        Long csId = SecurityUtils.getUserId();
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        // 1. 待指派订单数（PAID状态，未分配打手）
        long pendingAssign = orderService.count(scopedOrders(csId)
                .eq(Order::getStatus, "PAID")
                .isNull(Order::getPlayerId));
        result.put("pendingAssign", pendingAssign);

        // 2. 待处理投诉数（PENDING状态）
        long pendingComplaints = complaintService.count(scopedComplaints(csId)
                .eq(Complaint::getStatus, "PENDING"));
        result.put("pendingComplaints", pendingComplaints);

        // 3. 进行中仲裁数（PROCESSING状态）
        long processingComplaints = complaintService.count(scopedComplaints(csId)
                .eq(Complaint::getStatus, "PROCESSING"));
        result.put("processingComplaints", processingComplaints);

        // 3. 今日统计
        long todayOrders = orderService.count(scopedOrders(csId)
                .between(Order::getCreatedAt, todayStart, todayEnd));
        long todayCompleted = orderService.count(scopedOrders(csId)
                .eq(Order::getStatus, "COMPLETED")
                .between(Order::getCompleteTime, todayStart, todayEnd));
        long todayComplaints = complaintService.count(scopedComplaints(csId)
                .between(Complaint::getCreatedAt, todayStart, todayEnd));
        result.put("todayOrders", todayOrders);
        long validOrders = orderService.count(scopedOrders(csId)
                .between(Order::getCreatedAt, todayStart, todayEnd)
                .notIn(Order::getStatus, "PENDING_PAYMENT","CANCELLED","REFUNDING","REFUNDED"));
        result.put("validOrders", validOrders);
        result.put("todayCompleted", todayCompleted);
        result.put("todayComplaints", todayComplaints);

        // 4b. 今日成交额
        boolean canFinance = staffPermissions.allowed("finance.read");
        if (canFinance) {
            BigDecimal scopedAmount = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM `order` WHERE status NOT IN ('PENDING_PAYMENT','CANCELLED') " +
                            "AND created_at BETWEEN ? AND ? AND (cs_id=? OR cs_id IS NULL)",
                    BigDecimal.class, todayStart, todayEnd, csId);
            result.put("todayAmount", scopedAmount);
            result.put("collectedAmount", result.get("todayAmount"));
        } else {
            result.put("todayAmount", null);
            result.put("collectedAmount", null);
        }

        long onlinePlayers = playerService.count(new LambdaQueryWrapper<Player>()
                .eq(Player::getStatus, "ACTIVE").eq(Player::getIsOnline, 1));
        long offlinePlayers = playerService.count(new LambdaQueryWrapper<Player>()
                .eq(Player::getStatus, "ACTIVE").eq(Player::getIsOnline, 0));
        result.put("activePlayers", onlinePlayers);
        result.put("onlinePlayers", onlinePlayers);
        result.put("offlinePlayers", offlinePlayers);

        // 4d. 待回复聊天会话数（有未读消息的客服聊天会话）
        try {
            long encodedCs = ChatParticipantId.encodeCs(csId);
            Long pendingChatSessions = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT cs.id) FROM chat_session cs " +
                            "JOIN chat_message cm ON cm.session_id=cs.id " +
                            "WHERE (cs.id1=? OR cs.id2=?) AND cs.status='ACTIVE' " +
                            "AND cm.is_read=0 AND cm.sender_type!='CS'",
                    Long.class, encodedCs, encodedCs);
            result.put("pendingChatSessions", pendingChatSessions != null ? pendingChatSessions : 0L);
        } catch (Exception e) {
            result.put("pendingChatSessions", 0L);
        }

        // 5. 待审核提现
        result.put("pendingWithdraws", canFinance ? statsMapper.countPendingWithdraws() : null);

        // 6. 进行中订单
        long inProgress = orderService.count(scopedOrders(csId)
                .eq(Order::getStatus, "IN_PROGRESS"));
        result.put("inProgress", inProgress);

        // 7. 总用户数 & 总打手数
        result.put("totalUsers", statsMapper.countTotalUsers());
        result.put("totalPlayers", statsMapper.countTotalPlayers());

        try {
            Number visitors=jdbc.queryForObject("SELECT COUNT(DISTINCT visitor_key) FROM analytics_daily_visitor WHERE visit_date=CURRENT_DATE",Number.class);
            Number activeUsers=jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM analytics_daily_visitor WHERE visit_date=CURRENT_DATE AND user_id IS NOT NULL",Number.class);
            Number pendingRefunds=jdbc.queryForObject("SELECT COUNT(*) FROM refund_request r JOIN `order` o ON o.id=r.order_id " +
                    "WHERE r.status IN ('REQUESTED','CS_REVIEWING','CS_REVIEWED','FAILED') AND (o.cs_id=? OR o.cs_id IS NULL)",Number.class,csId);
            Number pendingEnrollments=jdbc.queryForObject("SELECT COUNT(*) FROM player WHERE status='PENDING' AND deleted=0",Number.class);
            Number pendingMaterials=jdbc.queryForObject("SELECT COUNT(*) FROM player_homepage_revision WHERE status IN ('PENDING_SYSTEM','PENDING_REVIEW')",Number.class);
            Number pendingIdentities=jdbc.queryForObject("SELECT COUNT(*) FROM identity_verification WHERE status='PENDING'",Number.class);
            Number pendingPrivacy=jdbc.queryForObject("SELECT COUNT(*) FROM privacy_request WHERE status='PENDING'",Number.class);
            Number pendingFulfillment=jdbc.queryForObject(
                    "SELECT (SELECT COUNT(*) FROM order_relay_request rr JOIN `order` o ON o.id=rr.order_id WHERE rr.status='PENDING' AND (o.cs_id=? OR o.cs_id IS NULL))" +
                            "+(SELECT COUNT(*) FROM player_replace_request pr JOIN `order` o ON o.id=pr.order_id WHERE pr.status='PENDING' AND (o.cs_id=? OR o.cs_id IS NULL))",
                    Number.class,csId,csId);
            Number onDutyCs=jdbc.queryForObject("SELECT COUNT(*) FROM admin a JOIN staff_shift s ON s.admin_id=a.id WHERE a.deleted=0 AND a.status=1 AND UPPER(a.role)='CS' AND s.online=1",Number.class);
            Number productCount=jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted=0",Number.class);
            Number categoryCount=jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE deleted=0",Number.class);
            result.put("todayVisitors",visitors==null?0:visitors.longValue());
            result.put("todayActiveUsers",activeUsers==null?0:activeUsers.longValue());
            result.put("pendingRefunds",pendingRefunds==null?0:pendingRefunds.longValue());
            result.put("pendingEnrollments",pendingEnrollments==null?0:pendingEnrollments.longValue());
            result.put("pendingMaterials",pendingMaterials==null?0:pendingMaterials.longValue());
            result.put("pendingIdentities",pendingIdentities==null?0:pendingIdentities.longValue());
            result.put("pendingPrivacy",pendingPrivacy==null?0:pendingPrivacy.longValue());
            result.put("pendingFulfillment",pendingFulfillment==null?0:pendingFulfillment.longValue());
            result.put("onDutyCs",onDutyCs==null?0:onDutyCs.longValue());
            result.put("productCount",productCount==null?0:productCount.longValue());
            result.put("categoryCount",categoryCount==null?0:categoryCount.longValue());
            long pendingAudits=(pendingEnrollments==null?0:pendingEnrollments.longValue())
                    +(pendingMaterials==null?0:pendingMaterials.longValue())
                    +(pendingIdentities==null?0:pendingIdentities.longValue())
                    +(pendingPrivacy==null?0:pendingPrivacy.longValue());
            result.put("pendingAudits",pendingAudits);
        } catch (Exception ignored) {
            result.putIfAbsent("todayVisitors",0L);result.putIfAbsent("todayActiveUsers",0L);
            result.putIfAbsent("pendingRefunds",0L);result.putIfAbsent("pendingEnrollments",0L);
            result.putIfAbsent("pendingMaterials",0L);result.putIfAbsent("pendingFulfillment",0L);
            result.putIfAbsent("pendingIdentities",0L);result.putIfAbsent("pendingPrivacy",0L);
            result.putIfAbsent("productCount",0L);result.putIfAbsent("categoryCount",0L);
            result.putIfAbsent("onDutyCs",0L);result.putIfAbsent("pendingAudits",0L);
        }

        // 8. 待指派订单列表预览（取前10）
        List<Order> pendingOrders = orderService.list(scopedOrders(csId)
                .eq(Order::getStatus, "PAID")
                .isNull(Order::getPlayerId)
                .orderByAsc(Order::getCreatedAt)
                .last("LIMIT 10"));
        result.put("pendingOrders", pendingOrders);

        return R.ok(result);
    }

    private LambdaQueryWrapper<Order> scopedOrders(Long csId) {
        return new LambdaQueryWrapper<Order>()
                .apply("(cs_id = {0} OR cs_id IS NULL)", csId);
    }

    private LambdaQueryWrapper<Complaint> scopedComplaints(Long csId) {
        return new LambdaQueryWrapper<Complaint>()
                .apply("order_id IN (SELECT id FROM `order` WHERE cs_id = {0} OR cs_id IS NULL)", csId);
    }
}
