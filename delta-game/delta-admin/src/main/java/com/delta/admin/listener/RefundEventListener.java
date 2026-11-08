package com.delta.admin.listener;

import com.delta.common.event.BusinessEvent;
import com.delta.pay.refund.OrderRefundWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Converts legacy cancellation events into a reviewed refund request. It never calls payment refund here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundEventListener {
    private final OrderRefundWorkflowService refunds;

    @EventListener
    public void onBusinessEvent(BusinessEvent event) {
        if (!"ORDER_CANCEL_REFUND".equals(event.getEventType())) return;

        Long orderId = event.getOrderId();
        log.info("收到退款申请事件: orderId={}", orderId);
        refunds.requestFromCancellation(orderId, event.getUserId(), event.getUserType(), event.getMessage());
    }
}
