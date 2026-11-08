package com.delta.admin;

import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.pay.refund.OrderRefundWorkflowService;
import com.delta.pay.refund.RefundCreateRequest;
import com.delta.pay.refund.RefundDecisionRequest;
import com.delta.pay.refund.RefundEvents;
import com.delta.pay.refund.RefundRequest;
import com.delta.pay.refund.RefundReviewRequest;
import com.delta.pay.refund.RefundStatus;
import com.delta.pay.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderRefundWorkflowTest {
    @Mock PaymentService payments;
    @Mock ApplicationEventPublisher events;
    @Mock CrossModuleMapper crossModuleMapper;

    private JdbcTemplate jdbc;
    private OrderRefundWorkflowService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:refund_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id BIGINT,amount DECIMAL(12,2),status VARCHAR(32),confirm_time TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)") ;
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,order_id BIGINT,biz_type VARCHAR(20),user_id BIGINT," +
                "amount DECIMAL(12,2),pay_method VARCHAR(32),status VARCHAR(32),refund_amount DECIMAL(12,2)," +
                "refund_no VARCHAR(64),refund_reason VARCHAR(255),refund_time TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE refund_request(id BIGINT AUTO_INCREMENT PRIMARY KEY,refund_no VARCHAR(40) NOT NULL," +
                "order_id BIGINT NOT NULL,order_no VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,payment_id BIGINT," +
                "payment_method VARCHAR(32),amount DECIMAL(12,2) NOT NULL,reason VARCHAR(500) NOT NULL,status VARCHAR(32) NOT NULL," +
                "order_status_before VARCHAR(32) NOT NULL,requester_type VARCHAR(16) NOT NULL,requester_id BIGINT NOT NULL," +
                "client_request_id VARCHAR(64),cs_reviewer_id BIGINT,cs_recommendation VARCHAR(16),cs_note VARCHAR(500)," +
                "admin_id BIGINT,admin_decision VARCHAR(16),admin_note VARCHAR(500),source_type VARCHAR(32),source_id BIGINT," +
                "channel_refund_no VARCHAR(64),last_error VARCHAR(500),active_order_id BIGINT," +
                "submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,cs_reviewed_at TIMESTAMP,admin_decided_at TIMESTAMP," +
                "processing_at TIMESTAMP,completed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,CONSTRAINT uk_refund_active UNIQUE(active_order_id)," +
                "CONSTRAINT uk_refund_client UNIQUE(requester_type,requester_id,client_request_id))") ;
        jdbc.execute("CREATE TABLE refund_request_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,refund_request_id BIGINT," +
                "action VARCHAR(40),from_status VARCHAR(32),to_status VARCHAR(32),operator_type VARCHAR(16)," +
                "operator_id BIGINT,note VARCHAR(500),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE complaint(id BIGINT PRIMARY KEY,status VARCHAR(20),result VARCHAR(30)," +
                "result_reason TEXT,resolved_at TIMESTAMP)");
        jdbc.update("INSERT INTO `order` VALUES(100,'O100',7,100.00,'PAID',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO payment VALUES(10,100,'ORDER',7,100.00,'BALANCE','PAID',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP)");
        service = new OrderRefundWorkflowService(jdbc, payments, events, crossModuleMapper);
    }

    @Test
    void customerRequestIsIdempotentAndCsCannotFinalApprove() {
        RefundCreateRequest input = input("req-1", "50.00", "客户申请部分退款");
        RefundRequest first = service.requestByCustomer(100L, 7L, "USER", input);
        RefundRequest repeated = service.requestByCustomer(100L, 7L, "USER", input);

        assertThat(first.getId()).isEqualTo(repeated.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=100", Long.class)).isEqualTo(1L);
        assertThat(first.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(first.getAmount()).isEqualByComparingTo("50.00");

        service.submitPreliminaryReview(first.getId(), 2L, "CS", review("APPROVE", "资料完整"));
        assertThatThrownBy(() -> service.approve(first.getId(), 2L, "CS", new RefundDecisionRequest()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403))
                .hasMessageContaining("仅限管理员");
        verify(payments, never()).executeApprovedRefund(anyLong());
    }

    @Test
    void adminApprovalRequiresCsReviewAndExecutesExactlyOnce() {
        RefundRequest request = service.requestByCustomer(100L, 7L, "USER", input("req-2", null, "客户申请全额退款"));
        assertThatThrownBy(() -> service.approve(request.getId(), 1L, "ADMIN", new RefundDecisionRequest()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409))
                .hasMessageContaining("客服初审");
        verify(payments, never()).executeApprovedRefund(anyLong());

        service.submitPreliminaryReview(request.getId(), 2L, "CS", review("APPROVE", "订单未履约"));
        doAnswer(invocation -> {
            jdbc.update("UPDATE payment SET status='REFUNDING',refund_amount=100.00,refund_no='P-RF-1'," +
                    "refund_reason='客户申请全额退款' WHERE id=10");
            return null;
        }).when(payments).executeApprovedRefund(request.getId());

        RefundRequest approved = service.approve(request.getId(), 1L, "ADMIN", decision("管理员终审通过"));
        RefundRequest repeated = service.approve(request.getId(), 1L, "ADMIN", decision("重复点击"));

        assertThat(approved.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(repeated.getId()).isEqualTo(request.getId());
        verify(payments, times(1)).executeApprovedRefund(request.getId());
        assertThat(jdbc.queryForObject("SELECT admin_decision FROM refund_request WHERE id=?", String.class, request.getId()))
                .isEqualTo("APPROVED");
    }

    @Test
    void amountAndOrderStateAreValidatedBeforeARequestCanBeCreated() {
        RefundCreateRequest tooLarge = input("req-3", "100.01", "申请超额退款");
        assertThatThrownBy(() -> service.requestByCustomer(100L, 7L, "USER", tooLarge))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400))
                .hasMessageContaining("订单金额");

        RefundCreateRequest fractional = input("req-4", "1.001", "三位小数金额");
        assertThatThrownBy(() -> service.requestByCustomer(100L, 7L, "USER", fractional))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400))
                .hasMessageContaining("两位小数");

        jdbc.update("UPDATE `order` SET status='CANCELLED' WHERE id=100");
        assertThatThrownBy(() -> service.requestByCustomer(100L, 7L, "USER", input("req-5", null, "取消订单退款")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409))
                .hasMessageContaining("状态不允许");
        verify(payments, never()).executeApprovedRefund(anyLong());
    }

    @Test
    void adminRejectionNeverRefundsAndReleasesTheActiveOrderLock() {
        RefundRequest request = service.requestByCustomer(100L, 7L, "USER", input("req-6", null, "客户申请退款"));
        service.submitPreliminaryReview(request.getId(), 2L, "CS", review("REJECT", "证据不足，建议驳回"));

        RefundRequest rejected = service.reject(request.getId(), 1L, "ADMIN", decision("不符合退款条件"));

        assertThat(rejected.getStatus()).isEqualTo(RefundStatus.ADMIN_REJECTED);
        assertThat(jdbc.queryForObject("SELECT active_order_id FROM refund_request WHERE id=?", Long.class, request.getId()))
                .isNull();
        verify(payments, never()).executeApprovedRefund(anyLong());
        RefundRequest second = service.requestByCustomer(100L, 7L, "USER", input("req-7", null, "补充凭证后重新申请"));
        assertThat(second.getId()).isNotEqualTo(request.getId());
    }

    @Test
    void paymentCallbacksUpdateRequestAndEmitClearSuccessOrFailureEvents() {
        RefundRequest request = service.requestByCustomer(100L, 7L, "USER", input("req-8", null, "客户申请退款"));
        jdbc.update("UPDATE refund_request SET status='PROCESSING',admin_id=1,admin_decision='APPROVED' WHERE id=?", request.getId());

        service.onPaymentRefunded(100L, 10L);

        assertThat(service.getRequired(request.getId()).getStatus()).isEqualTo(RefundStatus.REFUNDED);
        assertThat(jdbc.queryForObject("SELECT active_order_id FROM refund_request WHERE id=?", Long.class, request.getId()))
                .isNull();

        jdbc.update("INSERT INTO `order` VALUES(101,'O101',8,88.00,'PAID',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO payment VALUES(11,101,'ORDER',8,88.00,'WECHAT','PAID',NULL,NULL,NULL,NULL,CURRENT_TIMESTAMP)");
        RefundRequest failed = service.requestByCustomer(101L, 8L, "USER", input("req-9", null, "客户申请退款"));
        jdbc.update("UPDATE refund_request SET status='PROCESSING',admin_id=1,admin_decision='APPROVED' WHERE id=?", failed.getId());
        service.markPaymentFailed(101L, 11L, "支付渠道返回失败");
        assertThat(service.getRequired(failed.getId()).getStatus()).isEqualTo(RefundStatus.FAILED);

        ArgumentCaptor<BusinessEvent> captured = ArgumentCaptor.forClass(BusinessEvent.class);
        verify(events, atLeastOnce()).publishEvent(captured.capture());
        List<String> types = captured.getAllValues().stream().map(BusinessEvent::getEventType).toList();
        assertThat(types).contains(RefundEvents.SUCCEEDED, RefundEvents.FAILED);
        assertThat(captured.getAllValues()).allSatisfy(event ->
                assertThat(event.getMessage()).doesNotContain("13900000000", "11111111111"));
    }

    @Test
    void adminRejectionClosesComplaintRefundAndConfirmsTheDisputedOrder(){
        jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=100");
        jdbc.update("INSERT INTO complaint(id,status) VALUES(55,'PROCESSING')");

        RefundRequest request = service.createReviewedForComplaint(
                100L, 2L, new BigDecimal("50.00"), "投诉仲裁申请退款", 55L);
        jdbc.update("UPDATE `order` SET status='ARBITRATED' WHERE id=100");

        RefundRequest rejected = service.reject(request.getId(), 1L, "ADMIN", decision("凭证不足，不予退款"));

        assertThat(rejected.getStatus()).isEqualTo(RefundStatus.ADMIN_REJECTED);
        assertThat(jdbc.queryForObject("SELECT status FROM complaint WHERE id=55", String.class))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT result FROM complaint WHERE id=55", String.class))
                .isEqualTo("REJECT");
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=100", String.class))
                .isEqualTo("CONFIRMED");
        verify(events).publishEvent(argThat(event -> event instanceof com.delta.common.event.OrderConfirmedEvent));
    }

    private RefundCreateRequest input(String requestId, String amount, String reason) {
        RefundCreateRequest input = new RefundCreateRequest();
        input.setClientRequestId(requestId);
        input.setAmount(amount == null ? null : new BigDecimal(amount));
        input.setReason(reason);
        return input;
    }

    private RefundReviewRequest review(String recommendation, String note) {
        RefundReviewRequest review = new RefundReviewRequest();
        review.setRecommendation(recommendation);
        review.setNote(note);
        return review;
    }

    private RefundDecisionRequest decision(String note) {
        RefundDecisionRequest decision = new RefundDecisionRequest();
        decision.setNote(note);
        return decision;
    }
}
