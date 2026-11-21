package com.delta.marketing.listener;

import com.delta.common.event.BusinessEvent;
import com.delta.order.service.CouponRedemptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponPaymentListener {
    private final CouponRedemptionService coupons;

    @EventListener
    public void onOrderPaid(BusinessEvent event) {
        if ("ORDER_PAID".equals(event.getEventType()) && event.getOrderId() != null)
            coupons.consume(event.getOrderId());
    }
}
