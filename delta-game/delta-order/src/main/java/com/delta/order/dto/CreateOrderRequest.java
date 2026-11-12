package com.delta.order.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class CreateOrderRequest {
    @NotNull @Positive
    private Long productId;
    @Size(max=2000)
    private String specInfo;
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer=7,fraction=2)
    private BigDecimal amount;
    @Size(max=256)
    private String gameAccount;
    @Size(max=100)
    private String contact;
    @Size(max=2000)
    private String remark;
    @Positive
    private Long designatedPlayerId;
    @Positive
    private Integer playerLevelId;
    @Positive
    private Long couponId;
    @Size(max=50)
    private java.util.Map<String, String> extraFields;
}
