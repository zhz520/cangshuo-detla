package com.delta.order.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderQuote {
    private Long productId;
    private String productName;
    private Long designatedPlayerId;
    private Integer playerLevelId;
    private String playerLevelName;
    private BigDecimal baseAmount;
    private BigDecimal priceIncreaseRate;
    private BigDecimal priceIncreaseAmount;
    private String memberTier;
    private BigDecimal memberDiscountRate;
    private BigDecimal memberDiscountAmount;
    private Long couponId;
    private String couponName;
    private BigDecimal couponDiscountAmount;
    private BigDecimal amount;
}
