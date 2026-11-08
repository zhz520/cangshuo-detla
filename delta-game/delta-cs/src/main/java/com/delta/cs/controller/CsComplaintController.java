package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.event.BusinessEvent;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.utils.ImageListUtils;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.ComplaintService;
import com.delta.order.service.OrderService;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.pay.refund.RefundRequest;
import com.delta.order.service.OrderProgressService;
import com.delta.player.entity.Player;
import com.delta.player.service.PlayerService;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerWalletService;
import com.delta.pay.service.TransactionService;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.dto.ComplaintDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/cs/complaint")
@RequiredArgsConstructor
public class CsComplaintController {
    private final ComplaintService complaintService;
    private final OrderService orderService;
    private final OrderProgressService orderProgressService;
    private final OrderRefundWorkflowService refunds;
    private final com.delta.common.security.service.StaffPermissions staffPermissions;
    private final PlayerService playerService;
    private final PlayerWalletService playerWalletService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;
    private final CrossModuleMapper crossModuleMapper;

    /**
     * 客服代用户创建投诉工单
     */
    @OpLog(module = "complaint", operation = "创建投诉工单")
    @PostMapping("/create")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> create(@RequestBody Complaint complaint) {
        if(complaint==null) return R.fail(400,"投诉内容不能为空");
        // 1. 校验订单存在
        Order order = orderService.getById(complaint.getOrderId());
        if (order == null) return R.fail("订单不存在");
        // 2. 校验用户ID与订单匹配
        if (!order.getUserId().equals(complaint.getUserId())) return R.fail("用户ID与订单不匹配");
        // 3. 校验是否已有进行中的投诉
        long existCount = complaintService.count(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderId, complaint.getOrderId())
                .in(Complaint::getStatus, "PENDING", "PROCESSING", "APPEALING"));
        if (existCount > 0) return R.fail("该订单已有进行中的投诉");
        if(!java.util.Set.of("服务态度","服务质量","代练质量","账号安全","违规操作","其他").contains(complaint.getType()))
            return R.fail(400,"投诉类型不正确");
        String content=complaint.getContent()==null?"":complaint.getContent().trim();
        if(content.isEmpty()||content.length()>2000) return R.fail(400,"投诉内容须为1至2000字");
        if(complaint.getExpectedResult()!=null&&(complaint.getExpectedResult().length()>50||!java.util.Set.of("退款","重新服务","赔偿","道歉").contains(complaint.getExpectedResult())))
            return R.fail(400,"期望结果不正确");
        if(!java.util.Set.of("IN_PROGRESS","COMPLETED","CONFIRMED").contains(order.getStatus()))
            return R.fail(400,"当前订单状态不允许投诉");
        // 4. 创建投诉，只复制允许由客服填写的字段
        Complaint created=new Complaint();created.setOrderId(order.getId());created.setUserId(order.getUserId());
        created.setPlayerId(order.getPlayerId());created.setType(complaint.getType());created.setContent(content);
        created.setImages(ImageListUtils.normalizeLocal(complaint.getImages(), 9));created.setExpectedResult(complaint.getExpectedResult());
        created.setStatus("PENDING");created.setCreatedAt(LocalDateTime.now());
        complaintService.save(created);
        // 5. 更新订单状态为 DISPUTED（如果订单状态允许）
        orderService.disputeOrder(order.getId());
        eventPublisher.publishEvent(new BusinessEvent(this, "NEW_COMPLAINT", "CS",
                SecurityUtils.getUserId(), order.getId(), "客服已代客户创建投诉工单，等待受理"));
        return R.ok();
    }

    /**
     * 投诉详情
     */
    @GetMapping("/{id}")
    public R<ComplaintDetailVO> detail(@PathVariable Long id) {
        Complaint c = complaintService.getById(id);
        if (c == null) return R.fail("投诉不存在");
        if (c.getCsReadAt() == null) {
            c.setCsReadAt(LocalDateTime.now());
            complaintService.updateById(c);
        }

        Order order = orderService.getById(c.getOrderId());

        ComplaintDetailVO vo = new ComplaintDetailVO();
        vo.setComplaint(c);
        vo.setOrder(order);

        if (order != null) {
            // 用户信息
            vo.setUserNickname(crossModuleMapper.selectUserNickname(order.getUserId()));
            vo.setUserAvatar(crossModuleMapper.selectUserAvatar(order.getUserId()));
            // 打手信息
            if (order.getPlayerId() != null) {
                vo.setPlayerNickname(crossModuleMapper.selectPlayerNickname(order.getPlayerId()));
                vo.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(order.getPlayerId()));
            }
            // 订单进度
            java.util.List<OrderProgress> progress = orderProgressService.list(
                    new LambdaQueryWrapper<OrderProgress>()
                            .eq(OrderProgress::getOrderId, order.getId())
                            .orderByAsc(OrderProgress::getCreatedAt)
            );
            vo.setProgress(progress);
        }

        return R.ok(vo);
    }

    /**
     * 客服受理投诉：设置状态为PROCESSING（处理中）
     * H5端发送 JSON body: { remark: '...' }
     * MP端发送 JSON body: { remark: '...' }
     */
    @OpLog(module = "complaint", operation = "受理投诉")
    @PutMapping("/{id}/process")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> process(@PathVariable Long id, @RequestBody(required = false) java.util.Map<String, String> body) {
        Complaint c = complaintService.getOne(new LambdaQueryWrapper<Complaint>().eq(Complaint::getId,id).last("FOR UPDATE"));
        if (c == null) return R.fail("投诉不存在");
        if (!java.util.Set.of("PENDING","APPEALING").contains(c.getStatus())) return R.fail("只有待处理或申诉中的投诉可以受理");
        c.setStatus("PROCESSING");
        if (c.getCsReadAt() == null) c.setCsReadAt(LocalDateTime.now());
        complaintService.updateById(c);
        return R.ok();
    }

    @GetMapping("/list")
    public R<Page<Complaint>> list(PageQuery query, @RequestParam(required = false) Long taskId, @RequestParam(value = "status", required = false) String status) {
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .eq(taskId != null, Complaint::getId, taskId)
                .orderByDesc(Complaint::getCreatedAt);
        // status 为空字符串时不作为筛选条件
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Complaint::getStatus, status);
        }
        Page<Complaint> page = complaintService.page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return R.ok(page);
    }

    /**
     * 仲裁处理：根据结果执行退款/确认/重做/处罚
     */
    @OpLog(module = "complaint", operation = "仲裁处理")
    @PutMapping("/{id}/resolve")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> resolve(@PathVariable Long id, @RequestBody Complaint complaint) {
        Complaint c = complaintService.getOne(new LambdaQueryWrapper<Complaint>().eq(Complaint::getId,id).last("FOR UPDATE"));
        if (c == null) return R.fail("投诉不存在");
        if(!java.util.Set.of("PENDING","PROCESSING","APPEALING").contains(c.getStatus())) return R.fail(409,"投诉已处理");
        if(!java.util.Set.of("FULL_REFUND","PARTIAL_REFUND","REJECT","REDO").contains(complaint.getResult()==null?"":complaint.getResult())) return R.fail(400,"处理结果无效");
        if(java.util.Set.of("FULL_REFUND","PARTIAL_REFUND").contains(complaint.getResult()))staffPermissions.require("order.refund");
        String penalty = complaint.getPlayerPenalty();
        if (penalty != null && !penalty.isBlank()
                && !java.util.Set.of("NONE", "WARNING", "FREEZE").contains(penalty)) {
            return R.fail(400, "打手处罚类型无效");
        }
        if ("FREEZE".equals(penalty)) staffPermissions.require("player.manage");
        String resultReason=complaint.getResultReason()==null?"":complaint.getResultReason().trim();
        if(resultReason.length()>500) return R.fail(400,"处理说明不能超过500字");
        if("PARTIAL_REFUND".equals(complaint.getResult())){
            try{com.delta.common.utils.Money.positive(complaint.getRefundAmount());}
            catch(com.delta.common.exception.BusinessException error){return R.fail(error.getCode(),error.getMessage());}
        }
        Long operatorId = SecurityUtils.getUserId();
        Order order = orderService.getById(c.getOrderId());
        if (order == null) return R.fail(404,"订单不存在");
        RefundRequest refundRequest = null;
        if (java.util.Set.of("FULL_REFUND","PARTIAL_REFUND").contains(complaint.getResult())) {
            BigDecimal amount = "FULL_REFUND".equals(complaint.getResult())
                    ? order.getAmount() : complaint.getRefundAmount();
            try {
                refundRequest = refunds.createReviewedForComplaint(order.getId(), operatorId, amount,
                        "投诉仲裁申请退款：" + resultReason, c.getId());
            } catch (com.delta.common.exception.BusinessException error) {
                return R.fail(error.getCode(), error.getMessage());
            }
        }

        c.setResult(complaint.getResult());
        c.setRefundAmount(complaint.getRefundAmount());
        c.setResultReason(resultReason);
        c.setPlayerPenalty(complaint.getPlayerPenalty());
        c.setOperatorId(operatorId);
        c.setResolvedAt(refundRequest == null ? LocalDateTime.now() : null);
        c.setStatus(refundRequest == null ? "RESOLVED" : "PROCESSING");
        complaintService.updateById(c);

        // 更新订单仲裁状态
        orderService.arbitrateOrder(c.getOrderId(), complaint.getResult(), operatorId);

        // 根据仲裁结果分支处理
        String result = complaint.getResult();
        switch (result != null ? result : "") {
            case "FULL_REFUND" -> {
                notifyStatus(order, c, "COMPLAINT_REFUND_PENDING",
                        "全额退款申请已提交管理员终审", "订单仲裁结果：全额退款待管理员终审");
            }
            case "PARTIAL_REFUND" -> {
                notifyStatus(order, c, "COMPLAINT_REFUND_PENDING", "部分退款申请已提交管理员终审",
                        "订单仲裁结果：部分退款 " + complaint.getRefundAmount() + " 元待管理员终审");
            }
            case "REJECT" -> {
                // 驳回投诉：将订单状态改为CONFIRMED并触发结算
                String rejectFrom = order.getStatus();
                order.setStatus("CONFIRMED");
                order.setConfirmTime(LocalDateTime.now());
                order.setUpdatedAt(LocalDateTime.now());
                orderService.updateById(order);
                addProgress(order.getId(), rejectFrom, "CONFIRMED", "CS", operatorId, "仲裁驳回投诉，订单正常确认");
                eventPublisher.publishEvent(new OrderConfirmedEvent(this, order.getId(), "CS"));
                notifyBoth(order, c, "投诉已驳回，原因：" + resultReason,
                        "投诉已驳回，订单正常结算");
            }
            case "REDO" -> {
                // 重新服务：回退到ACCEPTED
                String redoFrom = order.getStatus();
                order.setStatus("ACCEPTED");
                order.setCompleteTime(null);
                order.setAutoConfirmDeadline(null);
                order.setUpdatedAt(LocalDateTime.now());
                orderService.updateById(order);
                addProgress(order.getId(), redoFrom, "ACCEPTED", "CS", operatorId, "仲裁要求重新服务");
                notifyBoth(order, c, "仲裁结果：打手将重新为您提供服务",
                        "订单仲裁结果：需重新提供服务");
            }
            default -> log.warn("未知仲裁结果类型: {}", result);
        }

        // 处罚打手
        if (complaint.getPlayerPenalty() != null && !"NONE".equals(complaint.getPlayerPenalty())) {
            handlePlayerPenalty(c.getOrderId(), complaint.getPlayerPenalty());
        }

        return R.ok();
    }

    private void notifyBoth(Order order, Complaint c, String userMsg, String playerMsg) {
        notifyStatus(order, c, "COMPLAINT_RESOLVED", userMsg, playerMsg);
    }

    private void notifyStatus(Order order, Complaint c, String eventType, String userMsg, String playerMsg) {
        if (order != null) {
            eventPublisher.publishEvent(new BusinessEvent(this, eventType,
                    "USER", order.getUserId(), c.getOrderId(), userMsg));
            if (order.getPlayerId() != null) {
                eventPublisher.publishEvent(new BusinessEvent(this, eventType,
                        "PLAYER", order.getPlayerId(), c.getOrderId(), playerMsg));
            }
        }
    }

    private void addProgress(Long orderId, String from, String to, String operatorType, Long operatorId, String content) {
        OrderProgress progress = new OrderProgress();
        progress.setOrderId(orderId);
        progress.setType("STATUS_CHANGE");
        progress.setFromStatus(from);
        progress.setToStatus(to);
        progress.setOperatorType(operatorType);
        progress.setOperatorId(operatorId);
        progress.setContent(content);
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
    }

    private void handlePlayerPenalty(Long orderId, String penalty) {
        Order order = orderService.getById(orderId);
        if (order == null || order.getPlayerId() == null) return;
        if ("FREEZE".equals(penalty)) {
            Player player = playerService.getOne(new LambdaQueryWrapper<Player>()
                    .eq(Player::getId, order.getPlayerId()).last("FOR UPDATE"));
            if (player != null) {
                player.setStatus("FROZEN");
                player.setIsOnline(0);
                playerService.updateById(player);
                log.info("打手{}已被冻结，关联订单{}", order.getPlayerId(), orderId);
            }
        }
    }

}
