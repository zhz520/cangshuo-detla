package com.delta.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.utils.ImageListUtils;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 打手端订单查询与操作（列表/进度更新/开始/完成）
 */
@RestController
@RequestMapping("/player/work")
@RequiredArgsConstructor
public class PlayerWorkController {
    private final OrderService orderService;
    private final OrderPlayerService orderPlayerService;
    private final OrderProgressService orderProgressService;
    private final com.delta.common.mapper.CrossModuleMapper crossModuleMapper;

    @GetMapping("/{id}")
    public R<Order> detail(@PathVariable Long id) {
        Long playerId=SecurityUtils.getUserId();Order order=orderService.getById(id);
        if(order==null) return R.fail(404,"订单不存在");
        boolean primary=playerId.equals(order.getPlayerId());
        boolean teammate=orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId,id)
                .eq(OrderPlayer::getPlayerId,playerId).eq(OrderPlayer::getStatus,"ACCEPTED"))>0;
        boolean available="PAID".equals(order.getStatus()) && order.getPlayerId()==null
                && (order.getDesignatedPlayerId()==null || playerId.equals(order.getDesignatedPlayerId()))
                && (order.getRequestedPlayerLevelId()==null || order.getRequestedPlayerLevelId().equals(crossModuleMapper.selectPlayerLevel(playerId)));
        if(!primary&&!teammate&&!available) return R.fail(404,"订单不存在");
        if(available || "ASSIGNED".equals(order.getStatus())) {
            order.setGameAccount(null);order.setContact(null);order.setRemark(null);order.setExtraFields(null);order.setUserId(null);
        } else {
            order.setUserNickname(crossModuleMapper.selectUserNickname(order.getUserId()));
            order.setUserAvatar(crossModuleMapper.selectUserAvatar(order.getUserId()));
            if (crossModuleMapper.selectUserIsGuest(order.getUserId()) == 1) order.setContact(null);
            order.setTeammates(orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId,id).eq(OrderPlayer::getRole,"TEAMMATE")));
            LocalDateTime now = LocalDateTime.now();
            order.getTeammates().forEach(op -> {
                op.setNickname(crossModuleMapper.selectPlayerNickname(op.getPlayerId()));
                op.setAvatar(crossModuleMapper.selectPlayerAvatar(op.getPlayerId()));
                if ("INVITED".equals(op.getStatus()) && op.getInviteDeadline() != null && !op.getInviteDeadline().isAfter(now))
                    op.setStatus("EXPIRED");
            });
        }
        return R.ok(order);
    }

    @GetMapping("/available")
    public R<Page<Order>> available(PageQuery query) {
        Long playerId=SecurityUtils.getUserId();
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, "PAID")
                .isNull(Order::getPlayerId)
                .and(w -> w.isNull(Order::getDesignatedPlayerId).or().eq(Order::getDesignatedPlayerId,playerId))
                .and(w -> w.isNull(Order::getRequestedPlayerLevelId).or()
                        .eq(Order::getRequestedPlayerLevelId,crossModuleMapper.selectPlayerLevel(playerId)))
                .orderByDesc(Order::getCreatedAt);
        Page<Order> page=orderService.page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        page.getRecords().forEach(o -> {o.setGameAccount(null);o.setContact(null);o.setRemark(null);o.setExtraFields(null);o.setUserId(null);});
        return R.ok(page);
    }

    @GetMapping("/my")
    public R<Page<Order>> myOrders(PageQuery query, @RequestParam(required = false) String status) {
        Long playerId = SecurityUtils.getUserId();
        // 查询作为队友参与的订单ID
        java.util.List<Long> teammateOrderIds = orderPlayerService.list(
                new LambdaQueryWrapper<OrderPlayer>()
                        .eq(OrderPlayer::getPlayerId, playerId)
                        .eq(OrderPlayer::getRole, "TEAMMATE")
                        .eq(OrderPlayer::getStatus, "ACCEPTED")
                        .select(OrderPlayer::getOrderId))
                .stream().map(OrderPlayer::getOrderId).toList();
        // 主接订单 OR 队友订单
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .and(w -> {
                    w.eq(Order::getPlayerId, playerId);
                    if (!teammateOrderIds.isEmpty()) {
                        w.or().in(Order::getId, teammateOrderIds);
                    }
                })
                .eq(status != null && !status.isEmpty(), Order::getStatus, status)
                .orderByDesc(Order::getCreatedAt);
        Page<Order> page = orderService.page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        fillUserInfo(page.getRecords());
        return R.ok(page);
    }

    private void fillUserInfo(java.util.List<Order> orders) {
        if (orders == null) return;
        for (Order o : orders) {
            if (o.getUserId() != null) {
                o.setUserNickname(crossModuleMapper.selectUserNickname(o.getUserId()));
                o.setUserAvatar(crossModuleMapper.selectUserAvatar(o.getUserId()));
                if (crossModuleMapper.selectUserIsGuest(o.getUserId()) == 1) o.setContact(null);
            }
        }
    }

    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable Long id) {
        orderService.startOrder(id, SecurityUtils.getUserId());
        return R.ok();
    }

    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable Long id, @RequestBody(required = false) ProgressRequest req) {
        orderService.completeOrder(id, SecurityUtils.getUserId(), req != null ? req.getImages() : null);
        return R.ok();
    }

    /**
     * 打手更新订单进度
     */
    @PostMapping("/{id}/progress")
    public R<Void> updateProgress(@PathVariable Long id, @RequestBody ProgressRequest req) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getById(id);
        if (order == null) return R.fail("订单不存在");
        if (!playerId.equals(order.getPlayerId())) return R.fail("无权操作");
        String content = req == null || req.getContent() == null ? "" : req.getContent().trim();
        if (content.length() > 2000) return R.fail(400,"进度内容不能超过2000字");
        String images;
        try { images = ImageListUtils.normalizeLocal(req == null ? null : req.getImages(), 9); }
        catch (com.delta.common.exception.BusinessException error) { return R.fail(error.getCode(), error.getMessage()); }
        if (content.isEmpty() && images == null) return R.fail(400,"请填写进度或上传图片");
        OrderProgress progress = new OrderProgress();
        progress.setOrderId(id);
        progress.setType("PROGRESS_UPDATE");
        progress.setOperatorType("PLAYER");
        progress.setOperatorId(playerId);
        progress.setContent(content.isEmpty() ? null : content);
        progress.setImages(images);
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
        return R.ok();
    }

    @Data
    public static class ProgressRequest {
        private String content;
        private String images;
    }
}
