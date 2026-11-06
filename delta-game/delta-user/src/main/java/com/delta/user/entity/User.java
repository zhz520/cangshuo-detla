package com.delta.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.delta.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user")
public class User extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String openid;
    private String nickname;
    private String avatar;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.delta.common.security.utils.MaskedPhoneSerializer.class)
    private String phone;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private String password;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long authVersion;
    /** 游客影子账户；仅用于无登录下单和历史关联，不是正式会员。 */
    private Integer isGuest;
    public boolean isPasswordSet() { return password!=null && !password.isBlank(); }
    private Integer status;
    @TableLogic
    private Integer deleted;

    /** 余额（非DB字段） */
    @TableField(exist = false)
    private BigDecimal balance;
}
