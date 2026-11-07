package com.delta.player.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.delta.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("player_wallet")
public class PlayerWallet extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long playerId;
    private BigDecimal balance;
    /** 订单已完成但在售后观察期内、尚不可提现的收益 */
    private BigDecimal pendingIncome;
    private BigDecimal frozenAmount;
    private BigDecimal totalIncome;
}
