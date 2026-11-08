package com.delta.common.notification;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Canonical business values that administrators may map into outbound notification templates. */
public final class NotificationTemplateSources {
    private NotificationTemplateSources() {}

    public static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.List.of(
            "message", "status", "time", "eventType",
            "customerId", "customerName", "customerPhone", "customerType", "customerStatus",
            "orderId", "orderNo", "amount", "orderAmount", "baseAmount", "priceIncreaseAmount",
            "productId", "productName", "productSpec", "orderStatus", "gameAccount", "orderContact",
            "orderRemark", "extraFields", "requiredPlayerCount", "requestedPlayerLevel", "orderCreatedAt",
            "payDeadline", "assignTime", "grabTime", "startTime", "completeTime", "confirmTime",
            "settleAmount", "settleTime", "orderUpdatedAt",
            "playerId", "playerName", "playerPhone", "playerLevel", "playerStatus", "playerOnline",
            "playerCompletedOrders",
            "refundNo", "refundAmount", "refundReason", "refundStatus", "refundPaymentMethod",
            "refundSubmittedAt", "refundCsReviewedAt", "refundAdminDecidedAt", "refundProcessingAt",
            "refundCompletedAt",
            "complaintType", "complaintContent", "complaintExpectedResult", "complaintStatus",
            "complaintResult", "complaintRefundAmount", "complaintResultReason", "complaintPlayerPenalty",
            "complaintResolvedAt", "complaintAppealReason", "complaintAppealResult",
            "withdrawId", "withdrawAmount", "withdrawStatus", "withdrawAccountType", "withdrawAccountName",
            "withdrawAccountNo", "withdrawMethod", "withdrawRejectReason", "withdrawAppliedAt",
            "withdrawProcessedAt", "withdrawProofUrl",
            "recipientId", "recipientType", "recipientName", "recipientPhone"
    )));
}
