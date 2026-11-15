package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.mapper.StatsMapper;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.customer.CsRoutingService;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.pay.refund.RefundCreateRequest;
import com.delta.pay.refund.RefundRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/cs/order")
@RequiredArgsConstructor
public class CsOrderController {
    private final OrderService orderService;
    private final OrderProgressService orderProgressService;
    private final CrossModuleMapper crossModuleMapper;
    private final StatsMapper statsMapper;
    private final OrderRefundWorkflowService refunds;
    private final CsRoutingService csRouting;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @GetMapping("/list")
    public R<Page<Order>> list(PageQuery query, @RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "orderNo", required = false) String orderNo,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "userId", required = false) Long userId,
                                @RequestParam(value = "playerId", required = false) Long playerId) {
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .apply("(cs_id = {0} OR cs_id IS NULL)", SecurityUtils.getUserId())
                .eq(userId != null, Order::getUserId, userId)
                .eq(playerId != null, Order::getPlayerId, playerId)
                .eq(status != null && !status.isEmpty(), Order::getStatus, status)
                .and((keyword != null && !keyword.isEmpty()) || (orderNo != null && !orderNo.isEmpty()), qw -> {
                    if (orderNo != null && !orderNo.isEmpty()) qw.like(Order::getOrderNo, orderNo);
                    else if (keyword != null && !keyword.isEmpty()) qw.like(Order::getOrderNo, keyword);
                })
                .orderByDesc(Order::getCreatedAt);
        Page<Order> page = orderService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (Order o : page.getRecords()) {
            if (o.getUserId() != null) {
                String nick = crossModuleMapper.selectUserNickname(o.getUserId());
                o.setUserNickname((nick != null && !nick.isEmpty()) ? nick : "用户" + o.getUserId());
                o.setUserAvatar(crossModuleMapper.selectUserAvatar(o.getUserId()));
                o.setUserPhone(crossModuleMapper.selectUserPhone(o.getUserId()));
            }
            if (o.getPlayerId() != null) {
                String pNick = crossModuleMapper.selectPlayerNickname(o.getPlayerId());
                o.setPlayerName((pNick != null && !pNick.isEmpty()) ? pNick : "接单员" + o.getPlayerId());
                o.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(o.getPlayerId()));
            }
            fillCs(o);
        }
        return R.ok(page);
    }

    @GetMapping("/{id}")
    public R<Order> detail(@PathVariable Long id) {
        csRouting.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        Order order = orderService.getById(id);
        if (order != null) {
            fillCs(order);
            if (order.getPlayerId() != null) {
                order.setPlayerName(crossModuleMapper.selectPlayerNickname(order.getPlayerId()));
                order.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(order.getPlayerId()));
            }
            if (order.getUserId() != null) {
                order.setUserNickname(crossModuleMapper.selectUserNickname(order.getUserId()));
                order.setUserAvatar(crossModuleMapper.selectUserAvatar(order.getUserId()));
                order.setUserPhone(crossModuleMapper.selectUserPhone(order.getUserId()));
            }
        }
        return R.ok(order);
    }

    private void fillCs(Order order) {
        try {
            java.util.List<Long> rows = jdbc.queryForList("SELECT cs_id FROM `order` WHERE id=?", Long.class, order.getId());
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

    @PostMapping("/{id}/assign/{playerId}")
    public R<Void> assign(@PathVariable Long id, @PathVariable Long playerId) {
        csRouting.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        orderService.assignOrder(id, playerId, "CS", SecurityUtils.getUserId());
        return R.ok();
    }

    @org.springframework.security.access.prepost.PreAuthorize("@staffPermissions.allowed('order.refund')")
    @com.delta.common.annotation.OpLog(module="order",operation="创建订单退款申请")
    @PostMapping("/{id}/refund")
    public R<RefundRequest> refund(@PathVariable Long id) {
        csRouting.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        RefundCreateRequest request = new RefundCreateRequest();
        request.setReason("客服发起订单退款申请");
        return R.ok(refunds.createByStaff(id, SecurityUtils.getUserId(), SecurityUtils.getUserType(), request));
    }

    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        csRouting.assertCsCanHandleOrder(id, SecurityUtils.getUserId());
        orderService.manualConfirmOrder(id, SecurityUtils.getUserId(), "CS");
        return R.ok();
    }

    /** 客服查看用户消费榜单（复用统计 Mapper） */
    @GetMapping("/spending-rank")
    public R<java.util.List<java.util.Map<String, Object>>> spendingRank(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return R.ok(statsMapper.userSpendingRank(Math.min(limit, 200)));
    }
}
