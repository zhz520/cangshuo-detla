package com.delta.order.service;

/** Authenticated, opaque metadata carried by a private complaint attachment token. */
public record ComplaintAttachmentToken(
        Long actorId,
        String actorType,
        Long orderId,
        String tenantId,
        String fileName,
        long issuedAt) {
}
