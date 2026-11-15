package com.delta.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.customer.CsRoutingService;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderService;
import com.delta.common.annotation.OpLog;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.pay.refund.RefundCreateRequest;
import com.delta.pay.refund.RefundRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {
    private final OrderService orderService;
    private final OrderProgressService orderProgressService;
    private final OrderPlayerService orderPlayerService;
    private final CrossModuleMapper crossModuleMapper;
    private final OrderRefundWorkflowService refunds;
    private final CsRoutingService csRouting;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @GetMapping("/list")
    public R<Page<Order>> list(PageQuery query,
                                @RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "orderNo", required = false) String orderNo,
                                @RequestParam(value = "playerId", required = false) Long playerId,
                                @RequestParam(value = "userId", required = false) Long userId,
                                @RequestParam(value = "csId", required = false) Long csId) {
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(status != null && !status.isEmpty(), Order::getStatus, status)
                .eq(playerId != null, Order::getPlayerId, playerId)
                .eq(userId != null, Order::getUserId, userId)
                .apply(csId != null && csId > 0, "cs_id = {0}", csId)
                .apply(csId != null && csId == 0, "cs_id IS NULL")
                .like(orderNo != null && !orderNo.isEmpty(), Order::getOrderNo, orderNo)
                .orderByDesc(Order::getCreatedAt);
        Page<Order> page = orderService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (Order o : page.getRecords()) {
            enrichOrder(o);
        }
        return R.ok(page);
    }

    @GetMapping("/{id}")
    public R<Order> detail(@PathVariable Long id) {
        Order order = orderService.getById(id);
        if (order != null) {
            enrichOrder(order);
            List<OrderPlayer> teammates = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                    .eq(OrderPlayer::getOrderId, id)
                    .eq(OrderPlayer::getRole, "TEAMMATE")
                    .orderByAsc(OrderPlayer::getInvitedAt)
                    .orderByAsc(OrderPlayer::getId));
            for (OrderPlayer teammate : teammates) {
                teammate.setNickname(crossModuleMapper.selectPlayerNickname(teammate.getPlayerId()));
                teammate.setAvatar(crossModuleMapper.selectPlayerAvatar(teammate.getPlayerId()));
            }
            order.setTeammates(teammates);
        }
        return R.ok(order);
    }

    private void enrichOrder(Order o) {
        if (o.getUserId() != null) {
            o.setUserNickname(crossModuleMapper.selectUserNickname(o.getUserId()));
            o.setUserAvatar(crossModuleMapper.selectUserAvatar(o.getUserId()));
            o.setUserPhone(crossModuleMapper.selectUserPhone(o.getUserId()));
        }
        if (o.getPlayerId() != null) {
            o.setPlayerName(crossModuleMapper.selectPlayerNickname(o.getPlayerId()));
            o.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(o.getPlayerId()));
        }
        fillCs(o);
    }

    private void fillCs(Order order) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT cs_id FROM `order` WHERE id=?", Long.class, order.getId());
            if (rows != null && !rows.isEmpty()) {
                order.setCsId(rows.get(0));
                if (rows.get(0) != null) order.setCsName(crossModuleMapper.selectAdminNickname(rows.get(0)));
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
        }
    }

    @GetMapping("/{id}/progress")
    public R<List<OrderProgress>> progress(@PathVariable Long id) {
        return R.ok(orderProgressService.list(new LambdaQueryWrapper<OrderProgress>()
                .eq(OrderProgress::getOrderId, id).orderByAsc(OrderProgress::getCreatedAt)));
    }

    @OpLog(module = "order", operation = "指派订单")
    @PostMapping("/{id}/assign/{playerId}")
    public R<Void> assign(@PathVariable Long id, @PathVariable Long playerId) {
        orderService.assignOrder(id, playerId, "ADMIN", SecurityUtils.getUserId());
        return R.ok();
    }

    @OpLog(module = "order", operation = "改派归属客服")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/cs")
    public R<Void> assignCs(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        Order order = orderService.getById(id);
        if (order == null) return R.fail(404, "订单不存在");
        Long csId = body == null ? null : body.get("csId");
        if (csId != null && csId <= 0) csId = null;
        if (csId != null && !csRouting.isActiveCs(csId)) return R.fail(400, "请选择启用中的客服账号");
        jdbc.update("UPDATE `order` SET cs_id=?,updated_at=? WHERE id=?", csId, LocalDateTime.now(), id);
        // Only unsettled orders may change financial attribution; settled ledger stays immutable.
        jdbc.update("UPDATE order_finance_snapshot SET cs_id=? WHERE order_id=? " +
                "AND NOT EXISTS(SELECT 1 FROM order_settlement_ledger WHERE order_id=?)", csId, id, id);
        OrderProgress progress = new OrderProgress();
        progress.setOrderId(id);
        progress.setType("CS_ASSIGN");
        progress.setOperatorType("ADMIN");
        progress.setOperatorId(SecurityUtils.getUserId());
        String name = csId == null ? "公共池" : crossModuleMapper.selectAdminNickname(csId);
        progress.setContent("管理员将归属客服调整为：" + (name == null || name.isBlank() ? "公共池" : name));
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
        return R.ok();
    }

    @OpLog(module = "order", operation = "创建订单退款申请")
    @org.springframework.security.access.prepost.PreAuthorize("@staffPermissions.allowed('order.refund')")
    @PostMapping("/{id}/refund")
    public R<RefundRequest> refund(@PathVariable Long id) {
        RefundCreateRequest request = new RefundCreateRequest();
        request.setReason("管理员发起订单退款申请");
        return R.ok(refunds.createByStaff(id, SecurityUtils.getUserId(), SecurityUtils.getUserType(), request));
    }

    @OpLog(module = "order", operation = "手动结单")
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        orderService.manualConfirmOrder(id, SecurityUtils.getUserId(), "ADMIN");
        return R.ok();
    }
}
