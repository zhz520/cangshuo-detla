package com.delta.pay.refund;

/** Stable event contract consumed by the existing notification pipeline. */
public final class RefundEvents {
    public static final String REQUESTED = "REFUND_REQUESTED";
    public static final String CS_REVIEWING = "REFUND_CS_REVIEWING";
    public static final String CS_REVIEWED = "REFUND_CS_REVIEWED";
    public static final String ADMIN_APPROVED = "REFUND_ADMIN_APPROVED";
    public static final String ADMIN_REJECTED = "REFUND_ADMIN_REJECTED";
    public static final String PROCESSING = "REFUND_PROCESSING";
    public static final String SUCCEEDED = "REFUND_SUCCEEDED";
    public static final String FAILED = "REFUND_FAILED";

    private RefundEvents() {}
}
