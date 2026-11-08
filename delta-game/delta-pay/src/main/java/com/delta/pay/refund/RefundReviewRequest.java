package com.delta.pay.refund;

import lombok.Data;

@Data
public class RefundReviewRequest {
    /** APPROVE or REJECT (recommendation only; ADMIN still makes the final decision). */
    private String recommendation;
    private String note;
}
