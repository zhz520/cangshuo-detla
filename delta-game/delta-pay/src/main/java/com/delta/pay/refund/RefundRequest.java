package com.delta.pay.refund;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RefundRequest {
    private Long id;
    private String refundNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private Long paymentId;
    private String paymentMethod;
    private BigDecimal amount;
    private String reason;
    private String status;
    private String orderStatusBefore;
    private String requesterType;
    private Long requesterId;
    private String clientRequestId;
    private Long csReviewerId;
    private String csRecommendation;
    private String csNote;
    private Long adminId;
    private String adminDecision;
    private String adminNote;
    private String sourceType;
    private Long sourceId;
    private String channelRefundNo;
    private String lastError;
    private LocalDateTime submittedAt;
    private LocalDateTime csReviewedAt;
    private LocalDateTime adminDecidedAt;
    private LocalDateTime processingAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
