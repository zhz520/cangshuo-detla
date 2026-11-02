package com.delta.player.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.delta.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("player_level")
public class PlayerLevel extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private Integer sortOrder;
    private Integer status;
    private Integer grabCaptchaRequired;
    /** 指定该等级下单时的涨价百分比，10 表示涨价 10%。 */
    private java.math.BigDecimal priceIncreaseRate;
    /** 提现前需要补缴的押金金额；0 表示该等级无需提现押金。 */
    private java.math.BigDecimal withdrawDepositAmount;
    @TableLogic
    private Integer deleted;
}
