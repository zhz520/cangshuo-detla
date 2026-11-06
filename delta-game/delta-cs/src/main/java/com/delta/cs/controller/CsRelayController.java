package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.event.BusinessEvent;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderRelayRequest;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderRelayRequestService;
import com.delta.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/cs/relay")
@RequiredArgsConstructor
public class CsRelayController {
    private final OrderRelayRequestService relayRequestService;
    private final OrderService orderService;
    private final OrderPlayerService orderPlayerService;
    private final CrossModuleMapper crossModuleMapper;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping("/list")
    public R<Page<OrderRelayRequest>> list(PageQuery query, @RequestParam(required = false) Long taskId,
                                            @RequestParam(value = "status", required = false) String status) {
        LambdaQueryWrapper<OrderRelayRequest> w = new LambdaQueryWrapper<OrderRelayRequest>()
                .eq(taskId != null, OrderRelayRequest::getId, taskId)
                .eq(status != null && !status.isEmpty(), OrderRelayRequest::getStatus, status)
                .orderByDesc(OrderRelayRequest::getCreatedAt);
        Page<OrderRelayRequest> page = relayRequestService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (OrderRelayRequest r : page.getRecords()) {
            Order order = orderService.getById(r.getOrderId());
            if (order != null) {
                r.setOrderNo(order.getOrderNo());
                r.setProductName(order.getProductName());
                r.setOrderAmount(order.getAmount());
            }
            r.setOriginalPlayerName(crossModuleMapper.selectPlayerNickname(r.getOriginalPlayerId()));
            if (r.getNewPlayerId() != null) {
                r.setNewPlayerName(crossModuleMapper.selectPlayerNickname(r.getNewPlayerId()));
            }
        }
        return R.ok(page);
    }

    @OpLog(module = "relay", operation = "审核通过接力申请")
    @PostMapping("/{id}/approve")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> approve(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        OrderRelayRequest relay = relayRequestService.getOne(new LambdaQueryWrapper<OrderRelayRequest>()
                .eq(OrderRelayRequest::getId,id).last("FOR UPDATE"));
        if (relay == null) return R.fail("申请不存在");
        if (!"PENDING".equals(relay.getStatus())) return R.fail("申请已处理");
        Long newPlayerId = body.get("newPlayerId");
        if (newPlayerId == null) return R.fail("请选择接力打手");

        Order order = orderService.getById(relay.getOrderId());
        if (order == null) return R.fail("订单不存在");
        if(!"IN_PROGRESS".equals(order.getStatus()))return R.fail(409,"当前订单状态不允许接力");
        if(!relay.getOriginalPlayerId().equals(order.getPlayerId()))return R.fail(409,"订单接单员已变化，请刷新后重试");
        if(newPlayerId.equals(relay.getOriginalPlayerId()))return R.fail(400,"不能选择原接单员接力");
        if(crossModuleMapper.lockActivePlayer(newPlayerId)==null)return R.fail(409,"接力打手当前不在线或不可接单");
        if(order.getRequestedPlayerLevelId()!=null&&!order.getRequestedPlayerLevelId().equals(crossModuleMapper.selectPlayerLevel(newPlayerId)))
            return R.fail(403,"接力打手等级不符合订单要求");
        int maxActive=5;
        try{maxActive=Math.max(1,Integer.parseInt(crossModuleMapper.selectConfigValue("order.max_active_per_player")));}catch(Exception ignored){}
        if(crossModuleMapper.countActivePlayerOrders(newPlayerId)>=maxActive)return R.fail(409,"接力打手当前订单已满");
        OrderPlayer originalOp = orderPlayerService.getOne(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, order.getId())
                .eq(OrderPlayer::getPlayerId, relay.getOriginalPlayerId())
                .eq(OrderPlayer::getRole, "PRIMARY")
                .last("LIMIT 1 FOR UPDATE"));
        if(originalOp==null)return R.fail(409,"原接单记录已变化，请刷新后重试");
        relay.setStatus("APPROVED");
        relay.setNewPlayerId(newPlayerId);
        relay.setReviewedBy(SecurityUtils.getUserId());
        relay.setReviewedAt(LocalDateTime.now());
        relay.setUpdatedAt(LocalDateTime.now());
        relayRequestService.updateById(relay);

        // Update original player's OrderPlayer role
        originalOp.setRole("RELAY_OUT");
        originalOp.setSplitType(relay.getSplitType());
        originalOp.setSplitAmount(relay.getSplitAmount());
        orderPlayerService.updateById(originalOp);

        // Set order to ASSIGNED for new player to accept
        orderService.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Order>()
                .eq(Order::getId,order.getId()).set(Order::getPlayerId,newPlayerId)
                .set(Order::getStatus,OrderStatusEnum.ASSIGNED.name()).set(Order::getAssignTime,LocalDateTime.now())
                .set(Order::getAcceptTime,null).set(Order::getStartTime,null).set(Order::getCompleteTime,null)
                .set(Order::getAutoConfirmDeadline,null).set(Order::getUpdatedAt,LocalDateTime.now()));

        eventPublisher.publishEvent(new BusinessEvent(this, "RELAY_APPROVED",
                "PLAYER", newPlayerId, order.getId(), "您收到一个接力订单，请尽快接单"));
        eventPublisher.publishEvent(new BusinessEvent(this, "RELAY_APPROVED",
                "PLAYER", relay.getOriginalPlayerId(), order.getId(), "接力申请已通过，新打手已指派"));
        return R.ok();
    }

    @OpLog(module = "relay", operation = "拒绝接力申请")
    @PostMapping("/{id}/reject")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        OrderRelayRequest relay = relayRequestService.getOne(new LambdaQueryWrapper<OrderRelayRequest>()
                .eq(OrderRelayRequest::getId,id).last("FOR UPDATE"));
        if (relay == null) return R.fail("申请不存在");
        if (!"PENDING".equals(relay.getStatus())) return R.fail("申请已处理");
        String reason=body != null ? body.getOrDefault("reason", "") : "";
        if(reason.length()>500)return R.fail(400,"拒绝原因不能超过500字");
        relay.setStatus("REJECTED");
        relay.setRejectReason(reason);
        relay.setReviewedBy(SecurityUtils.getUserId());
        relay.setReviewedAt(LocalDateTime.now());
        relay.setUpdatedAt(LocalDateTime.now());
        relayRequestService.updateById(relay);
        eventPublisher.publishEvent(new BusinessEvent(this, "RELAY_REJECTED",
                "PLAYER", relay.getOriginalPlayerId(), relay.getOrderId(), "接力申请被拒绝"));
        return R.ok();
    }
}
