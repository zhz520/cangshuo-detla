package com.delta.player.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.utils.ImageListUtils;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.player.entity.Player;
import com.delta.player.service.PlayerService;
import com.delta.player.service.OrderTeamService;
import com.delta.system.service.SysConfigService;
import com.delta.common.mapper.CrossModuleMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 打手订单操作接口（接单/拒绝指派/队友邀请）
 */
@RestController
@RequestMapping("/player/order")
@RequiredArgsConstructor
public class PlayerOrderController {
    private final OrderService orderService;
    private final OrderPlayerService orderPlayerService;
    private final OrderProgressService orderProgressService;
    private final PlayerService playerService;
    private final OrderTeamService teamwork;
    private final ApplicationEventPublisher eventPublisher;
    private final SysConfigService sysConfigService;
    private final CrossModuleMapper crossModuleMapper;
    private final com.delta.common.security.service.VerificationService verification;
    private final com.delta.system.service.RewardGrabRulesService grabRules;

    public record ReplaceTeammateRequest(Long teammatePlayerId) {}

    public record AcceptRequest(String captchaId,String captchaCode,String deviceId) {}

    @GetMapping("/{orderId}/accept-challenge")
    public R<java.util.Map<String,Object>> challenge(@PathVariable Long orderId,
            @RequestParam(required=false) String deviceId,jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");
        Long id=SecurityUtils.getUserId();Player p=playerService.getById(id);
        if(p==null || !"ACTIVE".equals(p.getStatus())) throw new BusinessException(403,"打手账号状态不允许接单");
        if(!Integer.valueOf(1).equals(p.getIsOnline())) throw new BusinessException(400,"当前处于休息中，请先开启接单状态后再接单");
        Order o=orderService.getById(orderId);
        if(o==null || !("PAID".equals(o.getStatus())&&o.getPlayerId()==null || "ASSIGNED".equals(o.getStatus())&&id.equals(o.getPlayerId()))
                || o.getDesignatedPlayerId()!=null&&!id.equals(o.getDesignatedPlayerId())) throw new BusinessException(409,"订单已被抢或当前不可接单");
        boolean required=!Integer.valueOf(0).equals(crossModuleMapper.selectGrabCaptchaRequired(id));
        if(o.getRequestedPlayerLevelId()!=null&&!o.getRequestedPlayerLevelId().equals(p.getLevel()))
            throw new BusinessException(403,"该订单仅限所选等级的打手接单");
        var rule=grabRules.get();var result=new java.util.HashMap<String,Object>();result.put("required",required);
        result.put("refreshSeconds",rule.captchaRefreshSeconds());result.put("expiresIn",rule.captchaSeconds());
        if(required) result.putAll(verification.grabCaptcha(id,orderId,deviceId,request.getHeader("User-Agent"),request.getRemoteAddr(),rule.captchaSeconds(),rule.captchaAttempts(),rule.captchaRefreshSeconds()));
        return R.ok(result);
    }

    /**
     * 接单大厅：浏览可接订单。
     * categoryIds：可选，逗号分隔的分类 id（主分类+子分类），筛选该主分类下所有子分类订单。
     */
    @GetMapping("/hall")
    public R<Page<Order>> hall(PageQuery query,
                               @RequestParam(required = false) String categoryIds) {
        Long playerId = SecurityUtils.getUserId();
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, "PAID")
                .isNull(Order::getPlayerId)
                // 显示未指定打手的订单 + 指定给当前打手的订单
                .and(aw -> aw.isNull(Order::getDesignatedPlayerId)
                        .or().eq(Order::getDesignatedPlayerId, playerId))
                .and(aw -> aw.isNull(Order::getRequestedPlayerLevelId)
                        .or().eq(Order::getRequestedPlayerLevelId, crossModuleMapper.selectPlayerLevel(playerId)))
                .orderByDesc(Order::getCreatedAt);
        List<Long> ids = parseCategoryIds(categoryIds);
        if (!ids.isEmpty()) {
            String inList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            w.inSql(Order::getProductId, "SELECT id FROM product WHERE category_id IN (" + inList + ")");
        }
        Page<Order> page = orderService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        // 接单前隐藏游戏账号（安全要求）
        page.getRecords().forEach(o -> {
            o.setGameAccount(null);
            o.setContact(null);o.setRemark(null);o.setExtraFields(null);o.setUserId(null);
        });
        return R.ok(page);
    }

    /**
     * 队友邀请列表（支持 type=received/sent）
     */
    @GetMapping("/invite-list")
    public R<List<OrderPlayer>> inviteList(@RequestParam(defaultValue = "received") String type) {
        Long playerId = SecurityUtils.getUserId();
        LambdaQueryWrapper<OrderPlayer> w = new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .orderByDesc(OrderPlayer::getInvitedAt);
        if ("sent".equals(type)) {
            w.eq(OrderPlayer::getInvitedBy, playerId).and(actor -> actor.isNull(OrderPlayer::getInvitedByType)
                    .or().eq(OrderPlayer::getInvitedByType, "PLAYER"));
        } else {
            w.eq(OrderPlayer::getPlayerId, playerId);
        }
        List<OrderPlayer> list = orderPlayerService.list(w);
        // 富化展示字段
        LocalDateTime now = LocalDateTime.now();
        for (OrderPlayer op : list) {
            if ("INVITED".equals(op.getStatus()) && op.getInviteDeadline() != null && !op.getInviteDeadline().isAfter(now))
                op.setStatus("EXPIRED");
            // 邀请人信息
            if ("ADMIN".equals(op.getInvitedByType()) || "CS".equals(op.getInvitedByType())) {
                op.setFromNickname("平台工作人员");
            } else if (op.getInvitedBy() != null) {
                Player from = playerService.getById(op.getInvitedBy());
                if (from != null) {
                    op.setFromNickname(from.getNickname());
                    op.setFromAvatar(from.getAvatar());
                }
            }
            // 被邀请人信息
            Player to = playerService.getById(op.getPlayerId());
            if (to != null) {
                op.setToNickname(to.getNickname());
                op.setToAvatar(to.getAvatar());
            }
            // 订单号
            Order order = orderService.getById(op.getOrderId());
            if (order != null) {
                op.setOrderNo(order.getOrderNo());
            }
        }
        return R.ok(list);
    }

    /**
     * 查看订单进度（打手端）
     */
    @GetMapping("/{orderId}/progress")
    public R<java.util.List<OrderProgress>> viewProgress(@PathVariable Long orderId) {
        Order order=orderService.getById(orderId);Long id=SecurityUtils.getUserId();
        boolean teammate=orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId,orderId).eq(OrderPlayer::getPlayerId,id).eq(OrderPlayer::getStatus,"ACCEPTED"))>0;
        if(order==null || !id.equals(order.getPlayerId()) && !teammate) return R.fail(404,"订单不存在");
        List<OrderProgress> history = orderProgressService.list(new LambdaQueryWrapper<OrderProgress>()
                .eq(OrderProgress::getOrderId, orderId).orderByAsc(OrderProgress::getCreatedAt));
        history.forEach(entry -> {
            if (entry.getType() != null && entry.getType().startsWith("TEAMMATE_")) entry.setRemark(null);
        });
        return R.ok(history);
    }

    /**
     * 打手接单（校验打手状态后委托给OrderService）
     */
    @PostMapping("/{orderId}/accept")
    public R<Void> accept(@PathVariable Long orderId,@RequestBody(required=false) AcceptRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        orderService.acceptOrder(orderId,SecurityUtils.getUserId(),body==null?null:body.captchaId(),
                body==null?null:body.captchaCode(),body==null?null:body.deviceId(),request.getHeader("User-Agent"),request.getRemoteAddr());
        return R.ok();
    }

    /**
     * 打手拒绝指派
     */
    @PostMapping("/{orderId}/reject-assign")
    public R<Void> rejectAssign(@PathVariable Long orderId,
                                 @RequestBody(required = false) java.util.Map<String, String> body,
                                 @RequestParam(value = "reason", required = false) String reasonParam) {
        Long playerId = SecurityUtils.getUserId();
        String reason = reasonParam;
        if (reason == null && body != null) {
            reason = body.getOrDefault("reason", "");
        }
        if (reason == null) reason = "";
        reason=reason.trim();
        if(reason.length()>500) return R.fail(400,"拒绝原因不能超过500字");
        orderService.rejectAssign(orderId, playerId, reason);
        return R.ok();
    }

    @PostMapping("/{orderId}/invite-teammate")
    public R<Void> inviteTeammate(@PathVariable Long orderId, @RequestBody OrderTeamService.Invite request) {
        teamwork.invite(orderId, playerActor(), request);
        return R.ok();
    }

    @PostMapping("/{orderId}/accept-invite")
    public R<Void> acceptInvite(@PathVariable Long orderId) {
        teamwork.accept(orderId, SecurityUtils.getUserId());
        return R.ok();
    }

    @PostMapping("/{orderId}/reject-invite")
    public R<Void> rejectInvite(@PathVariable Long orderId) {
        teamwork.reject(orderId, SecurityUtils.getUserId());
        return R.ok();
    }

    @GetMapping("/teammate/available")
    public R<OrderTeamService.Candidates> availableTeammates(PageQuery query, @RequestParam Long orderId,
            @RequestParam(required = false) String keyword) {
        return R.ok(teamwork.candidates(orderId, playerActor(), query.getPageNum(), query.getPageSize(), keyword));
    }

    /**
     * 打手开始服务（仅主接打手可操作）
     */
    @PostMapping("/{orderId}/start")
    public R<Void> startService(@PathVariable Long orderId) {
        Long playerId = SecurityUtils.getUserId();
        orderService.startOrder(orderId, playerId);
        return R.ok();
    }

    /**
     * 打手标记完成（仅主接打手可操作）
     */
    @PostMapping("/{orderId}/complete")
    public R<Void> completeService(@PathVariable Long orderId,
                                   @RequestBody(required = false) ProgressRequest req) {
        Long playerId = SecurityUtils.getUserId();
        orderService.completeOrder(orderId, playerId, req != null ? req.getImages() : null);
        return R.ok();
    }

    /**
     * 打手更新服务进度
     */
    @PostMapping("/{orderId}/progress")
    public R<Void> updateProgress(@PathVariable Long orderId, @RequestBody ProgressRequest req) {
        Long playerId = SecurityUtils.getUserId();
        // 校验操作者是该订单的参与打手
        long isMember = orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, playerId)
                .eq(OrderPlayer::getStatus, "ACCEPTED"));
        if (isMember == 0) return R.fail("您不是该订单的参与打手");
        Order order = orderService.getById(orderId);
        if (order == null || !"IN_PROGRESS".equals(order.getStatus())) {
            return R.fail("订单状态不允许更新进度");
        }
        String content = req == null || req.getContent() == null ? "" : req.getContent().trim();
        if (content.length() > 2000) return R.fail(400,"进度内容不能超过2000字");
        String images;
        try { images = ImageListUtils.normalizeLocal(req == null ? null : req.getImages(), 9); }
        catch (BusinessException error) { return R.fail(error.getCode(), error.getMessage()); }
        if (content.isEmpty() && images == null) return R.fail(400,"请填写进度或上传图片");
        OrderProgress progress = new OrderProgress();
        progress.setOrderId(orderId);
        progress.setType("PROGRESS_UPDATE");
        progress.setOperatorType("PLAYER");
        progress.setOperatorId(playerId);
        progress.setContent(content.isEmpty() ? null : content);
        progress.setImages(images);
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
        // 通知用户
        eventPublisher.publishEvent(new BusinessEvent(this, "PROGRESS_UPDATE",
                "USER", order.getUserId(), orderId, "打手更新了服务进度"));
        return R.ok();
    }

    /**
     * 换人—换自己：主接单员退出，订单回到接单大厅
     */
    @PostMapping("/{orderId}/replace-self")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> replaceSelf(@PathVariable Long orderId) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getById(orderId);
        if (order == null) return R.fail("订单不存在");
        if (!"IN_PROGRESS".equals(order.getStatus())) return R.fail("当前状态不允许换人");
        if (!playerId.equals(order.getPlayerId())) return R.fail("只有主接单员可以操作");
        // 清除主接单员，订单回到 PAID 等待重新接单（使用显式更新，确保字段被置空）
        orderService.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .set(Order::getPlayerId, null)
                .set(Order::getDesignatedPlayerId, null)
                .set(Order::getAssignTime, null)
                .set(Order::getAcceptTime, null)
                .set(Order::getStartTime, null)
                .set(Order::getTeammateDeadline, null)
                .set(Order::getStatus, "PAID")
                .set(Order::getUpdatedAt, LocalDateTime.now()));
        // 所有参与者标记为 REPLACED（包括主接单员和队友）
        markAllPlayersReplaced(orderId);
        addProgress(orderId, "PLAYER_REPLACED", "PLAYER", playerId, "接单员主动退出，订单回到接单大厅");
        // 通知用户：订单回到接单大厅
        eventPublisher.publishEvent(new BusinessEvent(this, "ORDER_BACK_TO_HALL",
                "USER", order.getUserId(), orderId, "接单员已退出，您的订单已重新回到接单大厅等待新的接单员"));
        return R.ok();
    }

    /**
     * 换人—两个都换：主接单员+队友全部退出
     */
    @PostMapping("/{orderId}/replace-all")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> replaceAll(@PathVariable Long orderId) {
        // 逻辑和 replace-self 一样，都是清除主接单员+队友回大厅
        return replaceSelf(orderId);
    }

    /** Compatibility for older H5 assets that only provide an immediate removal action. */
    @PostMapping("/{orderId}/replace-teammate")
    public R<Void> replaceTeammate(@PathVariable Long orderId,
                                   @RequestBody(required = false) ReplaceTeammateRequest request) {
        teamwork.legacyRemove(orderId, playerActor(), request == null ? null : request.teammatePlayerId());
        return R.ok();
    }

    @PostMapping("/{orderId}/teammates/{memberId}/revoke")
    public R<Void> revokeTeammate(@PathVariable Long orderId, @PathVariable Long memberId) {
        teamwork.revoke(orderId, playerActor(), memberId, null);
        return R.ok();
    }

    @PostMapping("/{orderId}/teammates/{memberId}/remove")
    public R<Void> removeTeammate(@PathVariable Long orderId, @PathVariable Long memberId,
                                  @RequestBody OrderTeamService.Reason request) {
        teamwork.remove(orderId, playerActor(), memberId, request == null ? null : request.reason());
        return R.ok();
    }

    private OrderTeamService.Actor playerActor() {
        return new OrderTeamService.Actor("PLAYER", SecurityUtils.getUserId());
    }

    /** 标记所有参与者为 REPLACED（replaceSelf/replaceAll 用） */
    private void markAllPlayersReplaced(Long orderId) {
        List<OrderPlayer> all = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .in(OrderPlayer::getStatus, "INVITED", "ACCEPTED"));
        for (OrderPlayer op : all) {
            op.setStatus("REPLACED");
            orderPlayerService.updateById(op);
        }
    }

    private void addProgress(Long orderId, String type, String operatorType, Long operatorId, String content) {
        OrderProgress progress = new OrderProgress();
        progress.setOrderId(orderId);
        progress.setType(type);
        progress.setOperatorType(operatorType);
        progress.setOperatorId(operatorId);
        progress.setContent(content);
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
    }

    /** 解析逗号分隔的分类 id 为 List，忽略无效值 */
    private static List<Long> parseCategoryIds(String categoryIds) {
        if (categoryIds == null || categoryIds.trim().isEmpty()) return Collections.emptyList();
        if(categoryIds.length()>2000) throw new BusinessException(400,"分类筛选条件过长");
        List<Long> list = new ArrayList<>();
        for (String s : categoryIds.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try {
                list.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) { }
            if(list.size()>=100) break;
        }
        return list;
    }

    @Data
    public static class ProgressRequest {
        private String content;
        private String images;
    }

}
