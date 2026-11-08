package com.delta.pay.refund;

import com.delta.common.event.BusinessEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps the durable refund request in sync with the existing payment callback/recovery path. */
@Component
@RequiredArgsConstructor
public class RefundResultListener {
    private final OrderRefundWorkflowService refunds;

    @EventListener
    public void onRefundResult(BusinessEvent event) {
        if (event == null || event.getOrderId() == null) return;
        if ("ORDER_REFUNDED".equals(event.getEventType())) {
            refunds.onPaymentRefunded(event.getOrderId(), null);
        } else if ("ORDER_REFUND_FAILED".equals(event.getEventType())) {
            refunds.markPaymentFailed(event.getOrderId(), null, event.getMessage());
        }
    }
}
