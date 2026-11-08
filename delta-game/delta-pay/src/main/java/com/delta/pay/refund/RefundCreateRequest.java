package com.delta.pay.refund;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RefundCreateRequest {
    private BigDecimal amount;
    private String reason;
    private String clientRequestId;
}
