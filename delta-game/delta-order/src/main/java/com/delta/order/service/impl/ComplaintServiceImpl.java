package com.delta.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.common.domain.PageQuery;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.dto.ComplaintDetailVO;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderProgress;
import com.delta.order.mapper.ComplaintMapper;
import com.delta.order.service.ComplaintAttachmentService;
import com.delta.order.service.ComplaintService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl extends ServiceImpl<ComplaintMapper, Complaint> implements ComplaintService {
    private final OrderService orderService;
    private final JdbcTemplate jdbc;
    private final OrderProgressService orderProgressService;
    private final CrossModuleMapper crossModuleMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ComplaintAttachmentService attachmentService;

    @Override
    public boolean canAccessOrder(Long orderId, Long actorId, String actorType) {
        if (orderId == null || actorId == null) return false;
        Order order = orderService.getById(orderId);
        return canAccessUser(actorId, actorType, order == null ? null : order.getUserId());
    }

    @Override
    public boolean canAccessUser(Long actorId, String actorType, Long ownerUserId) {
        if (actorId == null || ownerUserId == null) return false;
        return actorId.equals(ownerUserId)
                || ("USER".equals(actorType)
                    && crossModuleMapper.countGuestLink(actorId, ownerUserId) > 0);
    }

    @Override
    public List<Long> ownerScope(Long actorId, String actorType) {
        List<Long> owners = new ArrayList<>();
        owners.add(actorId);
        if ("USER".equals(actorType)) {
            owners.addAll(crossModuleMapper.selectLinkedGuestUserIds(actorId));
        }
        return owners;
    }

    @Override
    public Page<Complaint> pageForOwners(PageQuery query, Collection<Long> ownerIds) {
        return page(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<Complaint>()
                        .in(Complaint::getUserId, ownerIds)
                        .orderByDesc(Complaint::getCreatedAt));
    }

    @Override
    public ComplaintDetailVO getDetail(Complaint complaint) {
        Order order = orderService.getById(complaint.getOrderId());
        ComplaintDetailVO vo = new ComplaintDetailVO();
        vo.setComplaint(complaint);
        vo.setOrder(order);
        vo.setAttachments(attachmentService.views(complaint.getImages(), complaint.getOrderId()));
        vo.setAppealAttachments(attachmentService.views(complaint.getAppealImages(), complaint.getOrderId()));

        if (order != null) {
            vo.setUserNickname(crossModuleMapper.selectUserNickname(order.getUserId()));
            vo.setUserAvatar(crossModuleMapper.selectUserAvatar(order.getUserId()));
            if (order.getPlayerId() != null) {
                vo.setPlayerNickname(crossModuleMapper.selectPlayerNickname(order.getPlayerId()));
                vo.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(order.getPlayerId()));
            }
            List<OrderProgress> progress = orderProgressService.list(
                    new LambdaQueryWrapper<OrderProgress>()
                            .eq(OrderProgress::getOrderId, order.getId())
                            .orderByAsc(OrderProgress::getCreatedAt)
            );
            vo.setProgress(progress);
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createComplaint(Complaint complaint, Long actorId, String actorType) {
        Order order = orderService.getById(complaint.getOrderId());
        if (!canAccessUser(actorId, actorType, order == null ? null : order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }

        String status = order.getStatus();
        boolean afterSaleWindow = false;
        try {
            if ("CONFIRMED".equals(status) || "REVIEWED".equals(status)) {
                Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM player_income_settlement WHERE order_id=? " +
                        "AND status='PENDING' AND release_at>CURRENT_TIMESTAMP", Long.class, order.getId());
                afterSaleWindow = pending != null && pending > 0;
            }
        } catch (Exception ignored) {}
        if (!"IN_PROGRESS".equals(status) && !"COMPLETED".equals(status) && !afterSaleWindow) {
            throw new BusinessException("当前订单状态不允许投诉");
        }

        long existCount = count(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getOrderId, complaint.getOrderId())
                .in(Complaint::getStatus, "PENDING", "PROCESSING", "APPEALING"));
        if (existCount > 0) throw new BusinessException("该订单已有进行中的投诉");

        complaint.setUserId(order.getUserId());
        complaint.setPlayerId(order.getPlayerId());
        complaint.setImages(attachmentService.bindToComplaint(order.getId(), actorId, actorType,
                complaint.getAttachmentIds(), complaint.getImages()));
        complaint.setStatus("PENDING");
        save(complaint);

        orderService.disputeOrder(complaint.getOrderId());
        eventPublisher.publishEvent(new BusinessEvent(this, "NEW_COMPLAINT",
                "CS", null, complaint.getOrderId(), "新投诉待处理，订单ID: " + complaint.getOrderId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appealComplaint(Complaint complaint, Complaint appeal, Long actorId, String actorType) {
        if (!"RESOLVED".equals(complaint.getStatus())) {
            throw new BusinessException("当前状态不允许申诉");
        }
        if (!canAccessUser(actorId, actorType, complaint.getUserId())) {
            throw new BusinessException(404, "投诉不存在");
        }
        String reason = appeal.getAppealReason() == null ? "" : appeal.getAppealReason().trim();
        if (reason.isEmpty() || reason.length() > 2000) {
            throw new BusinessException(400, "申诉原因须为1至2000字");
        }
        complaint.setAppealReason(reason);
        complaint.setAppealImages(attachmentService.bindToComplaint(complaint.getOrderId(), actorId, actorType,
                appeal.getAttachmentIds(), appeal.getAppealImages()));
        complaint.setStatus("APPEALING");
        updateById(complaint);
        eventPublisher.publishEvent(new BusinessEvent(this, "COMPLAINT_APPEALED", "USER",
                complaint.getUserId(), complaint.getOrderId(), "投诉申诉已提交，等待客服重新处理"));
        if (complaint.getPlayerId() != null) {
            eventPublisher.publishEvent(new BusinessEvent(this, "COMPLAINT_APPEALED", "PLAYER",
                    complaint.getPlayerId(), complaint.getOrderId(), "客户已提交投诉申诉，等待客服重新处理"));
        }
    }
}
