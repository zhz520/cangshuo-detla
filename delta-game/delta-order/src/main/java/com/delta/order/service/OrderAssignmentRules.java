package com.delta.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.exception.BusinessException;
import com.delta.order.entity.Order;
import com.delta.system.service.SysConfigService;

import java.util.function.Supplier;

public final class OrderAssignmentRules {
    private OrderAssignmentRules() {}

    public static void requireAssignable(Order order) {
        if (order == null) throw new BusinessException(404, "订单不存在");
        String status = order.getStatus();
        if (!OrderStatusEnum.PAID.name().equals(status) && !OrderStatusEnum.ASSIGNED.name().equals(status))
            throw new BusinessException("当前状态不允许指派, 实际:" + status);
    }

    public static int maxConcurrent(SysConfigService config) {
        return Integer.parseInt(config.getConfigValue("order.max_active_per_player", "5"));
    }

    // Existing fulfillment rule: exact equality to the saved order ID, with no restriction when absent.
    // Do not use the level's name, sort order or current sale/enable status to decide eligibility.
    public static <T> void restrictToRequestedLevel(Order order, LambdaQueryWrapper<T> query,
                                                     SFunction<T, Integer> playerLevel) {
        Integer required = order.getRequestedPlayerLevelId();
        query.eq(required != null, playerLevel, required);
    }

    public static void requireRequestedLevel(Order order, Supplier<Integer> currentPlayerLevel) {
        Integer required = order.getRequestedPlayerLevelId();
        if (required != null && !required.equals(currentPlayerLevel.get()))
            throw new BusinessException(403, "该订单仅限所选等级的打手接单");
    }
}
