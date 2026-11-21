package com.delta.order.service.impl;

import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.mapper.ComplaintMapper;
import com.delta.order.service.ComplaintAttachmentService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceImplTest {
    @Mock OrderService orderService;
    @Mock JdbcTemplate jdbc;
    @Mock OrderProgressService orderProgressService;
    @Mock CrossModuleMapper crossModuleMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ComplaintMapper complaintMapper;
    @Mock ComplaintAttachmentService attachmentService;

    private ComplaintServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ComplaintServiceImpl(orderService, jdbc, orderProgressService, crossModuleMapper,
                eventPublisher, attachmentService);
        ReflectionTestUtils.setField(service, "baseMapper", complaintMapper);
    }

    @Test
    void createComplaintUsesAfterSaleWindowAndPublishesTheOriginalEvent() {
        Order order = order(10L, 7L, 3L, "CONFIRMED");
        when(orderService.getById(10L)).thenReturn(order);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10L))).thenReturn(1L);
        when(complaintMapper.selectCount(any())).thenReturn(0L);

        Complaint complaint = complaint(10L);
        service.createComplaint(complaint, 7L, "USER");

        assertThat(complaint.getUserId()).isEqualTo(7L);
        assertThat(complaint.getPlayerId()).isEqualTo(3L);
        assertThat(complaint.getStatus()).isEqualTo("PENDING");
        verify(complaintMapper).insert(complaint);
        verify(orderService).disputeOrder(10L);

        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        BusinessEvent published = (BusinessEvent) event.getValue();
        assertThat(published.getEventType()).isEqualTo("NEW_COMPLAINT");
        assertThat(published.getOrderId()).isEqualTo(10L);
    }

    @Test
    void createComplaintRejectsAnExistingActiveComplaint() {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L, 3L, "IN_PROGRESS"));
        when(complaintMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createComplaint(complaint(10L), 7L, "USER"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该订单已有进行中的投诉");

        verify(complaintMapper, never()).insert(any(Complaint.class));
        verify(orderService, never()).disputeOrder(10L);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void appealComplaintUpdatesTheStatusAndPublishesBothNotifications() {
        Complaint complaint = complaint(10L);
        complaint.setId(5L);
        complaint.setUserId(7L);
        complaint.setPlayerId(3L);
        complaint.setStatus("RESOLVED");
        Complaint appeal = new Complaint();
        appeal.setAppealReason("  申请重新核对  ");
        when(complaintMapper.updateById(complaint)).thenReturn(1);

        service.appealComplaint(complaint, appeal, 7L, "USER");

        assertThat(complaint.getStatus()).isEqualTo("APPEALING");
        assertThat(complaint.getAppealReason()).isEqualTo("申请重新核对");
        verify(complaintMapper).updateById(complaint);
        verify(eventPublisher, times(2)).publishEvent(any(ApplicationEvent.class));
    }

    private static Order order(Long id, Long userId, Long playerId, String status) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setPlayerId(playerId);
        order.setStatus(status);
        return order;
    }

    private static Complaint complaint(Long orderId) {
        Complaint complaint = new Complaint();
        complaint.setOrderId(orderId);
        complaint.setType("其他");
        complaint.setContent("投诉内容");
        return complaint;
    }
}
