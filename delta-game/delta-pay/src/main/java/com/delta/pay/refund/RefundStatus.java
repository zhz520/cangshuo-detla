package com.delta.pay.refund;

import java.util.Set;

/** Durable states for the customer/CS/admin two-stage refund workflow. */
public final class RefundStatus {
    public static final String REQUESTED = "REQUESTED";
    public static final String CS_REVIEWING = "CS_REVIEWING";
    public static final String CS_REVIEWED = "CS_REVIEWED";
    public static final String ADMIN_APPROVED = "ADMIN_APPROVED";
    public static final String ADMIN_REJECTED = "ADMIN_REJECTED";
    public static final String PROCESSING = "PROCESSING";
    public static final String REFUNDED = "REFUNDED";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";

    public static final Set<String> ACTIVE = Set.of(
            REQUESTED, CS_REVIEWING, CS_REVIEWED, ADMIN_APPROVED, PROCESSING, FAILED);

    private RefundStatus() {}
}
