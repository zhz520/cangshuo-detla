package com.delta.pay.refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Customer-safe projection that excludes staff notes, operator IDs and channel internals. */
public record CustomerRefundView(
        Long id,
        String refundNo,
        Long orderId,
        String orderNo,
        BigDecimal amount,
        String reason,
        String status,
        String publicNote,
        LocalDateTime submittedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CustomerRefundView from(RefundRequest request) {
        String note = null;
        if (RefundStatus.ADMIN_REJECTED.equals(request.getStatus()) || RefundStatus.REJECTED.equals(request.getStatus())) {
            note = request.getAdminNote();
        } else if (RefundStatus.FAILED.equals(request.getStatus())) {
            note = "原路退款暂未完成，请联系客服处理";
        }
        return new CustomerRefundView(request.getId(), request.getRefundNo(), request.getOrderId(),
                request.getOrderNo(), request.getAmount(), request.getReason(), request.getStatus(), note,
                request.getSubmittedAt(), request.getCompletedAt(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
