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
import com.delta.common.chat.service.ChatSessionService;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.player.entity.Player;
import com.delta.player.service.PlayerService;
import com.delta.system.service.SysConfigService;
import com.delta.common.mapper.CrossModuleMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ChatSessionService chatSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final SysConfigService sysConfigService;
    private final CrossModuleMapper crossModuleMapper;
    private final com.delta.common.security.service.VerificationService verification;
    private final com.delta.system.service.RewardGrabRulesService grabRules;

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
            w.eq(OrderPlayer::getInvitedBy, playerId);
        } else {
            w.eq(OrderPlayer::getPlayerId, playerId);
        }
        List<OrderPlayer> list = orderPlayerService.list(w);
        // 富化展示字段
        for (OrderPlayer op : list) {
            // 邀请人信息
            if (op.getInvitedBy() != null) {
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
        return R.ok(orderProgressService.list(
                new LambdaQueryWrapper<OrderProgress>()
                        .eq(OrderProgress::getOrderId, orderId)
                        .orderByAsc(OrderProgress::getCreatedAt)));
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

    /**
     * 邀请队友
     */
    @PostMapping("/{orderId}/invite-teammate")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> inviteTeammate(@PathVariable Long orderId, @RequestBody InviteRequest req) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId).last("FOR UPDATE"));
        if (order == null) return R.fail(404, "订单不存在");
        if (!"true".equalsIgnoreCase(sysConfigService.getConfigValue("order.cooperation.enabled", "true")))
            return R.fail(403, "协同打单功能已关闭，暂不接受新的队友邀请");
        String st = order.getStatus();
        // 接单后（ACCEPTED）、等待队友（WAITING_TEAMMATE）以及服务进行中（IN_PROGRESS）均允许邀请队友
        if (!OrderStatusEnum.ACCEPTED.name().equals(st)
                && !OrderStatusEnum.WAITING_TEAMMATE.name().equals(st)
                && !OrderStatusEnum.IN_PROGRESS.name().equals(st)) {
            return R.fail("当前状态不允许邀请队友");
        }
        if (!playerId.equals(order.getPlayerId())) return R.fail("只有主打手可以邀请队友");
        if (req == null || req.getInviteePlayerId() == null) return R.fail(400,"请选择要邀请的打手");

        // 校验不能邀请自己
        if (req.getInviteePlayerId().equals(playerId)) {
            return R.fail("不能邀请自己作为队友");
        }

        // 校验被邀请打手
        Player invitee = playerService.getById(req.getInviteePlayerId());
        if (invitee == null || !"ACTIVE".equals(invitee.getStatus()) || !Integer.valueOf(1).equals(invitee.getIsOnline())) {
            return R.fail("被邀请打手不可用");
        }

        // 自定义分成金额校验
        String splitType=req.getSplitType();
        if(!java.util.Set.of("FIFTY_FIFTY","FORTY_SIXTY","THIRTY_SEVENTY","CUSTOM").contains(splitType))
            return R.fail(400,"请选择有效的分成方式");
        if ("CUSTOM".equals(splitType)) {
            try{com.delta.common.utils.Money.positive(req.getCustomAmount());}
            catch(BusinessException error){return R.fail(error.getCode(),error.getMessage());}
            // 校验不超过打手总收入（订单金额 * (1 - 抽成比例)）
            java.math.BigDecimal rate = order.getCommissionRate() != null
                    ? order.getCommissionRate()
                    : new java.math.BigDecimal(sysConfigService.getConfigValue("settlement.commission_rate", "0.2"));
            java.math.BigDecimal playerTotal = order.getAmount()
                    .multiply(java.math.BigDecimal.ONE.subtract(rate));
            if (req.getCustomAmount().compareTo(playerTotal) > 0) {
                return R.fail("自定义金额不可超过打手总收入");
            }
        }

        LocalDateTime now=LocalDateTime.now();
        List<OrderPlayer> pending=orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId,orderId).eq(OrderPlayer::getRole,"TEAMMATE")
                .eq(OrderPlayer::getStatus,"INVITED"));
        for(OrderPlayer item:pending)if(item.getInviteDeadline()!=null&&item.getInviteDeadline().isBefore(now)){
            item.setStatus("EXPIRED");item.setRejectedAt(now);orderPlayerService.updateById(item);
        }
        int requiredTeam=Math.max(0,(order.getRequiredPlayerCount()==null?1:order.getRequiredPlayerCount())-1);
        int maxTeam=Math.max(1,requiredTeam);
        long occupiedTeam=orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId,orderId).eq(OrderPlayer::getRole,"TEAMMATE")
                .in(OrderPlayer::getStatus,"INVITED","ACCEPTED"));
        if(occupiedTeam>=maxTeam)return R.fail(409,"当前订单队伍人数已满");

        // 检查是否已邀请
        long existing = orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, req.getInviteePlayerId()));
        if (existing > 0) return R.fail(409, "已邀请过该打手");

        validateTeamAllocation(order, splitType, req.getCustomAmount());

        OrderPlayer op = new OrderPlayer();
        op.setOrderId(orderId);
        op.setPlayerId(req.getInviteePlayerId());
        op.setRole("TEAMMATE");
        op.setStatus("INVITED");
        op.setSplitType(splitType);
        op.setSplitAmount("CUSTOM".equals(splitType)?req.getCustomAmount():null);
        op.setInvitedBy(playerId);
        op.setInvitedAt(LocalDateTime.now());
        // 队友邀请有效期：5分钟内未接受则自动失效
        op.setInviteDeadline(now.plusMinutes(5));
        op.setCreatedAt(now);
        orderPlayerService.save(op);

        // 如果当前是 ACCEPTED，自动转入 WAITING_TEAMMATE
        if (OrderStatusEnum.ACCEPTED.name().equals(order.getStatus()) && requiredTeam > 0) {
            order.setStatus(OrderStatusEnum.WAITING_TEAMMATE.name());
            order.setTeammateDeadline(now.plusHours(2));
            order.setUpdatedAt(now);
            orderService.updateById(order);
        }

        // 记录进度（分成方式用中文）
        java.util.Map<String, String> splitNames = java.util.Map.of(
                "FIFTY_FIFTY", "五五开", "FORTY_SIXTY", "四六开",
                "THIRTY_SEVENTY", "三七开", "CUSTOM", "自定义金额");
        String splitCn = splitNames.getOrDefault(req.getSplitType(), "平分");
        addProgress(orderId, "TEAMMATE_INVITED", "PLAYER", playerId,
                "邀请队友 " + invitee.getNickname() + "，分成方式: " + splitCn);
        // 通知被邀请队友
        eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_INVITED",
                "PLAYER", req.getInviteePlayerId(), orderId,
                "您收到一份组队邀请，请尽快确认"));
        return R.ok();
    }

    /**
     * 接受队友邀请
     */
    @PostMapping("/{orderId}/accept-invite")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> acceptInvite(@PathVariable Long orderId) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getById(orderId);
        if (order == null || !java.util.Set.of("ACCEPTED", "WAITING_TEAMMATE", "IN_PROGRESS").contains(order.getStatus())) {
            throw new BusinessException(409, "当前订单状态不允许接受队友邀请");
        }
        OrderPlayer op = orderPlayerService.getOne(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, playerId)
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .eq(OrderPlayer::getStatus, "INVITED").last("FOR UPDATE"));
        if (op == null) return R.fail("未找到邀请记录");
        if (op.getInviteDeadline() != null && op.getInviteDeadline().isBefore(LocalDateTime.now())) {
            return R.fail("邀请已过期");
        }
        if (crossModuleMapper.lockActivePlayer(playerId) == null) {
            throw new BusinessException(403, "打手未上班、未通过审核或账号已冻结，无法接单");
        }
        int requiredTeam=Math.max(0,(order.getRequiredPlayerCount()==null?1:order.getRequiredPlayerCount())-1);
        int maxTeam=Math.max(1,requiredTeam);
        long occupiedTeam=orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId,orderId).eq(OrderPlayer::getRole,"TEAMMATE")
                .eq(OrderPlayer::getStatus,"ACCEPTED"));
        if(occupiedTeam>=maxTeam)throw new BusinessException(409,"当前订单队伍人数已满");
        int max = Integer.parseInt(sysConfigService.getConfigValue("order.max_active_per_player", "5"));
        if (crossModuleMapper.countActivePlayerOrders(playerId) >= max) {
            throw new BusinessException(400, "您当前进行中的订单已达上限(" + max + "单)");
        }
        op.setStatus("ACCEPTED");
        op.setAcceptedAt(LocalDateTime.now());
        orderPlayerService.updateById(op);

        // 记录进度
        addProgress(orderId, "TEAMMATE_ACCEPTED", "PLAYER", playerId, "队友确认邀请时的分成方案并接受邀请");

        // 创建打手-队友私聊会话（编码ID：PLAYER=2e9+id）
        chatSessionService.findOrCreate(ChatParticipantId.encodePlayer(order.getPlayerId()), ChatParticipantId.encodePlayer(playerId));

        long acceptedTeam = orderPlayerService.count(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .eq(OrderPlayer::getStatus, "ACCEPTED"));
        boolean teamReady = acceptedTeam >= requiredTeam;
        if (!OrderStatusEnum.IN_PROGRESS.name().equals(order.getStatus())) {
            order.setStatus(teamReady ? OrderStatusEnum.ACCEPTED.name() : OrderStatusEnum.WAITING_TEAMMATE.name());
            order.setUpdatedAt(LocalDateTime.now());
            orderService.updateById(order);
        }
        eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_ACCEPTED",
                "PLAYER", order.getPlayerId(), orderId,
                teamReady ? "队友已加入，队伍已满员，可以开始服务"
                        : "队友已加入，队伍还需" + (requiredTeam - acceptedTeam) + "名队友"));
        if (teamReady && !OrderStatusEnum.IN_PROGRESS.name().equals(order.getStatus())) {
            eventPublisher.publishEvent(new BusinessEvent(this, "TEAM_READY",
                    "USER", order.getUserId(), orderId, "打手已组队完成"));
        }
        return R.ok();
    }

    /**
     * 拒绝队友邀请
     */
    @PostMapping("/{orderId}/reject-invite")
    public R<Void> rejectInvite(@PathVariable Long orderId) {
        Long playerId = SecurityUtils.getUserId();
        OrderPlayer op = orderPlayerService.getOne(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, playerId)
                .eq(OrderPlayer::getStatus, "INVITED"));
        if (op == null) return R.fail("未找到邀请记录");
        op.setStatus("REJECTED");
        op.setRejectedAt(LocalDateTime.now());
        orderPlayerService.updateById(op);

        // 记录进度
        addProgress(orderId, "TEAMMATE_REJECTED", "PLAYER", playerId, "队友拒绝邀请");
        // 通知主接打手
        Order order = orderService.getById(orderId);
        if (order != null && order.getPlayerId() != null) {
            eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_REJECTED",
                    "PLAYER", order.getPlayerId(), orderId, "队友已拒绝邀请，请邀请其他队友"));
        }
        return R.ok();
    }

    /**
     * 查询可邀请的队友列表（复用派单页面查询模式）
     */
    @GetMapping("/teammate/available")
    public R<java.util.Map<String, Object>> availableTeammates(
            PageQuery query,
            @RequestParam Long orderId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getById(orderId);
        if (order == null) return R.fail("订单不存在");
        if (!playerId.equals(order.getPlayerId())) return R.fail("只有主接打手可查看");

        // 查出该订单已邀请过的打手ID
        List<Long> invitedPlayerIds = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .select(OrderPlayer::getPlayerId))
                .stream().map(OrderPlayer::getPlayerId).toList();

        // 查询ACTIVE打手，排除自己和已邀请的，支持搜索
        LambdaQueryWrapper<Player> w = new LambdaQueryWrapper<Player>()
                .eq(Player::getStatus, "ACTIVE").eq(Player::getIsOnline,1)
                .ne(Player::getId, playerId);
        if (!invitedPlayerIds.isEmpty()) {
            w.notIn(Player::getId, invitedPlayerIds);
        }
        if (keyword != null && !keyword.isEmpty()) {
            if(keyword.length()>50)return R.fail(400,"搜索内容过长");
            w.and(qw -> qw.like(Player::getNickname, keyword));
        }
        w.orderByDesc(Player::getAvgRating);
        Page<Player> page = playerService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        // 填充统计数据（和派单页面一样）
        for (Player p : page.getRecords()) {
            p.setCompletedOrders(crossModuleMapper.selectPlayerCompletedOrders(p.getId()));
            p.setActiveOrders(crossModuleMapper.selectPlayerActiveOrders(p.getId()));
        }
        int maxConcurrent = Integer.parseInt(sysConfigService.getConfigValue("order.max_active_per_player", "5"));
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        for(Player p:page.getRecords()){p.setOpenid(null);p.setRealName(null);p.setPhone(null);p.setProofImages(null);p.setDepositPaymentNo(null);p.setBalance(null);}
        result.put("players", page);
        result.put("maxConcurrent", maxConcurrent);
        return R.ok(result);
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

    /**
     * 换人—换队友：踢掉队友，主接单员继续，可重新邀请
     */
    @PostMapping("/{orderId}/replace-teammate")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> replaceTeammate(@PathVariable Long orderId,
                                   @RequestBody(required = false) ReplaceTeammateRequest req) {
        Long playerId = SecurityUtils.getUserId();
        Order order = orderService.getOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId).last("FOR UPDATE"));
        if (order == null) return R.fail(404, "订单不存在");
        if (!java.util.Set.of("ACCEPTED", "WAITING_TEAMMATE", "IN_PROGRESS").contains(order.getStatus()))
            return R.fail(409, "当前状态不允许更换协同打手");
        if (!playerId.equals(order.getPlayerId())) return R.fail(403, "只有主接单员可以更换协同打手");
        if (!"true".equalsIgnoreCase(sysConfigService.getConfigValue("order.cooperation.enabled", "true")))
            return R.fail(403, "协同打单功能已关闭，不能更换协同打手");

        List<OrderPlayer> activeTeammates = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .in(OrderPlayer::getStatus, "INVITED", "ACCEPTED")
                .last("FOR UPDATE"));
        if (activeTeammates.isEmpty()) return R.fail(409, "当前没有可更换的协同打手");

        // 兼容旧客户端：不传新打手时只移除现有队友，主接单员保留。
        if (req == null || req.getReplacementPlayerId() == null) {
            List<OrderPlayer> replaced = markTeammatesReplaced(orderId);
            addProgress(orderId, "TEAMMATE_REPLACED", "PLAYER", playerId, "主接单员移除协同打手，可重新邀请");
            for (OrderPlayer op : replaced) {
                eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_REMOVED",
                        "PLAYER", op.getPlayerId(), orderId, "您已被主接单员从订单中移除"));
            }
            return R.ok();
        }

        OrderPlayer oldTeammate;
        if (req.getTeammatePlayerId() != null) {
            oldTeammate = activeTeammates.stream()
                    .filter(item -> req.getTeammatePlayerId().equals(item.getPlayerId()))
                    .findFirst().orElse(null);
            if (oldTeammate == null) return R.fail(404, "指定的协同打手不在当前队伍中");
        } else {
            if (activeTeammates.size() > 1) return R.fail(400, "请指定要替换的协同打手");
            oldTeammate = activeTeammates.get(0);
        }
        Long replacementId = req.getReplacementPlayerId();
        if (replacementId.equals(playerId)) return R.fail(400, "不能把协同打手替换成主接单员");
        if (replacementId.equals(oldTeammate.getPlayerId())) return R.fail(400, "新协同打手不能与被替换打手相同");
        boolean duplicate = activeTeammates.stream().anyMatch(item -> replacementId.equals(item.getPlayerId()));
        if (duplicate) return R.fail(409, "该打手已在当前队伍中");
        Player replacement = playerService.getById(replacementId);
        if (replacement == null || !"ACTIVE".equals(replacement.getStatus()) || !Integer.valueOf(1).equals(replacement.getIsOnline()))
            return R.fail(409, "替补打手不可用或当前不在线");
        int max = Integer.parseInt(sysConfigService.getConfigValue("order.max_active_per_player", "5"));
        if (crossModuleMapper.countActivePlayerOrders(replacementId) >= max)
            return R.fail(400, "替补打手当前进行中的订单已达上限(" + max + "单)");

        String splitType = req.getSplitType();
        if (!java.util.Set.of("FIFTY_FIFTY", "FORTY_SIXTY", "THIRTY_SEVENTY", "CUSTOM").contains(splitType))
            return R.fail(400, "请选择有效的分成方式");
        if ("CUSTOM".equals(splitType)) {
            try {
                com.delta.common.utils.Money.positive(req.getCustomAmount());
            } catch (BusinessException error) {
                return R.fail(error.getCode(), error.getMessage());
            }
            BigDecimal rate = order.getCommissionRate() != null
                    ? order.getCommissionRate()
                    : new BigDecimal(sysConfigService.getConfigValue("settlement.commission_rate", "0.2"));
            BigDecimal playerTotal = order.getAmount().multiply(BigDecimal.ONE.subtract(rate));
            if (req.getCustomAmount().compareTo(playerTotal) > 0) return R.fail(409, "自定义金额不可超过打手总收入");
        }

        LocalDateTime now = LocalDateTime.now();
        oldTeammate.setStatus("REPLACED");
        oldTeammate.setRejectedAt(now);
        orderPlayerService.updateById(oldTeammate);
        validateTeamAllocation(order, splitType, req.getCustomAmount());

        OrderPlayer replacementRow = new OrderPlayer();
        replacementRow.setOrderId(orderId);
        replacementRow.setPlayerId(replacementId);
        replacementRow.setRole("TEAMMATE");
        replacementRow.setStatus("INVITED");
        replacementRow.setSplitType(splitType);
        replacementRow.setSplitAmount("CUSTOM".equals(splitType) ? req.getCustomAmount() : null);
        replacementRow.setInvitedBy(playerId);
        replacementRow.setInvitedAt(now);
        replacementRow.setInviteDeadline(now.plusMinutes(5));
        replacementRow.setCreatedAt(now);
        orderPlayerService.save(replacementRow);

        int requiredTeam = Math.max(0, (order.getRequiredPlayerCount() == null ? 1 : order.getRequiredPlayerCount()) - 1);
        if (OrderStatusEnum.ACCEPTED.name().equals(order.getStatus()) && requiredTeam > 0) {
            order.setStatus(OrderStatusEnum.WAITING_TEAMMATE.name());
            order.setTeammateDeadline(now.plusHours(2));
            order.setUpdatedAt(now);
            orderService.updateById(order);
        }
        Player oldPlayer = playerService.getById(oldTeammate.getPlayerId());
        addProgress(orderId, "TEAMMATE_REPLACED", "PLAYER", playerId,
                "更换协同打手：" + (oldPlayer == null ? oldTeammate.getPlayerId() : oldPlayer.getNickname()) + " → " + replacement.getNickname());
        eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_REMOVED",
                "PLAYER", oldTeammate.getPlayerId(), orderId, "您已被主接单员从订单中移除"));
        eventPublisher.publishEvent(new BusinessEvent(this, "TEAMMATE_INVITED",
                "PLAYER", replacementId, orderId, "您收到一份替补协同邀请，请尽快确认"));
        return R.ok();
    }

    /** 标记所有参与者为 REPLACED（replaceSelf/replaceAll 用） */
    private void markAllPlayersReplaced(Long orderId) {
        List<OrderPlayer> all = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .in(OrderPlayer::getStatus, "INVITED", "ACCEPTED"));
        for (OrderPlayer op : all) {
            op.setStatus("REPLACED");
            op.setRejectedAt(LocalDateTime.now());
            orderPlayerService.updateById(op);
        }
    }

    /** 只标记队友为 REPLACED，返回被标记的列表（replaceTeammate 用） */
    private List<OrderPlayer> markTeammatesReplaced(Long orderId) {
        List<OrderPlayer> teammates = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "TEAMMATE")
                .in(OrderPlayer::getStatus, "INVITED", "ACCEPTED"));
        for (OrderPlayer op : teammates) {
            op.setStatus("REPLACED");
            op.setRejectedAt(LocalDateTime.now());
            orderPlayerService.updateById(op);
        }
        return teammates;
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

    private void validateTeamAllocation(Order order, String splitType, BigDecimal customAmount) {
        int requiredTeam = Math.max(0, (order.getRequiredPlayerCount() == null ? 1 : order.getRequiredPlayerCount()) - 1);
        BigDecimal commissionRate = order.getCommissionRate() != null
                ? order.getCommissionRate()
                : new BigDecimal(sysConfigService.getConfigValue("settlement.commission_rate", "0.2"));
        if (commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0)
            throw new BusinessException("抽佣比例无效");
        BigDecimal playerTotal = order.getAmount()
                .multiply(BigDecimal.ONE.subtract(commissionRate))
                .setScale(2, RoundingMode.HALF_UP);
        if (playerTotal.signum() <= 0) throw new BusinessException(409, "订单没有可分配的收益");

        List<OrderPlayer> participants = orderPlayerService.list(new LambdaQueryWrapper<OrderPlayer>()
                .eq(OrderPlayer::getOrderId, order.getId())
                .in(OrderPlayer::getRole, "TEAMMATE", "RELAY_OUT")
                .in(OrderPlayer::getStatus, "INVITED", "ACCEPTED"));
        long relayOutCount = participants.stream().filter(item -> "RELAY_OUT".equals(item.getRole())).count();
        int sharingPeople = Math.max(1, requiredTeam) + (int) relayOutCount;
        BigDecimal reserved = participants.stream()
                .map(item -> estimatedShare(item.getSplitType(), item.getSplitAmount(), playerTotal, sharingPeople))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        reserved = reserved.add(estimatedShare(splitType, customAmount, playerTotal, sharingPeople));
        if (reserved.compareTo(playerTotal) > 0)
            throw new BusinessException(409, "队伍分成总额超过可结算收益");
    }

    private BigDecimal estimatedShare(String splitType, BigDecimal customAmount,
                                      BigDecimal playerTotal, int sharingPeople) {
        if ("CUSTOM".equals(splitType))
            return customAmount == null ? BigDecimal.ZERO : customAmount.setScale(2, RoundingMode.HALF_UP);
        if ("FORTY_SIXTY".equals(splitType))
            return playerTotal.multiply(new BigDecimal("0.4")).setScale(2, RoundingMode.HALF_UP);
        if ("THIRTY_SEVENTY".equals(splitType))
            return playerTotal.multiply(new BigDecimal("0.3")).setScale(2, RoundingMode.HALF_UP);
        return playerTotal.divide(BigDecimal.valueOf(sharingPeople + 1L), 2, RoundingMode.HALF_UP);
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
    public static class InviteRequest {
        private Long inviteePlayerId;
        /** 分成方式: FIFTY_FIFTY / FORTY_SIXTY / THIRTY_SEVENTY / CUSTOM */
        private String splitType;
        /** 自定义金额（仅splitType=CUSTOM时有效） */
        private java.math.BigDecimal customAmount;
    }

    @Data
    public static class ReplaceTeammateRequest {
        private Long teammatePlayerId;
        private Long replacementPlayerId;
        private String splitType;
        private java.math.BigDecimal customAmount;
    }

    @Data
    public static class ProgressRequest {
        private String content;
        private String images;
    }

}
