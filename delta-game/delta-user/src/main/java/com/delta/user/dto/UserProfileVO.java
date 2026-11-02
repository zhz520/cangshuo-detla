package com.delta.user.dto;

import lombok.Data;

@Data
public class UserProfileVO {
    private Long id;
    private String nickname;
    private String avatar;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.delta.common.security.utils.MaskedPhoneSerializer.class)
    private String phone;
    private java.math.BigDecimal balance;
    private Integer unreadMessageCount;
}
