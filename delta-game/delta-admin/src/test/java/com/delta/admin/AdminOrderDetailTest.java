package com.delta.admin;

import com.delta.admin.controller.AdminOrderController;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.pay.refund.OrderRefundWorkflowService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOrderDetailTest {
    @Test
    void detailIncludesTeammateProfileStatusAndSplitPlan() {
        OrderService orders = mock(OrderService.class);
        OrderPlayerService players = mock(OrderPlayerService.class);
        CrossModuleMapper cross = mock(CrossModuleMapper.class);
        Order order = new Order();
        order.setId(9L);
        order.setOrderNo("O-9");
        order.setUserId(7L);
        order.setPlayerId(3L);
        OrderPlayer teammate = new OrderPlayer();
        teammate.setId(21L);
        teammate.setOrderId(9L);
        teammate.setPlayerId(5L);
        teammate.setRole("TEAMMATE");
        teammate.setStatus("ACCEPTED");
        teammate.setSplitType("FORTY_SIXTY");
        teammate.setSplitAmount(new BigDecimal("12.50"));
        teammate.setInvitedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        teammate.setAcceptedAt(LocalDateTime.of(2026, 11, 12, 10, 2));
        when(orders.getById(9L)).thenReturn(order);
        when(players.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(teammate));
        when(cross.selectUserNickname(7L)).thenReturn("下单用户");
        when(cross.selectUserPhone(7L)).thenReturn("13900000000");
        when(cross.selectPlayerNickname(3L)).thenReturn("主打手");
        when(cross.selectPlayerNickname(5L)).thenReturn("协同打手");
        when(cross.selectPlayerAvatar(5L)).thenReturn("/file/player.png");
        var controller = new AdminOrderController(orders, mock(OrderProgressService.class), players, cross,
                mock(OrderRefundWorkflowService.class));

        Order detail = controller.detail(9L).getData();

        assertThat(detail.getPlayerName()).isEqualTo("主打手");
        assertThat(detail.getTeammates()).singleElement().satisfies(member -> {
            assertThat(member.getNickname()).isEqualTo("协同打手");
            assertThat(member.getAvatar()).isEqualTo("/file/player.png");
            assertThat(member.getStatus()).isEqualTo("ACCEPTED");
            assertThat(member.getSplitType()).isEqualTo("FORTY_SIXTY");
            assertThat(member.getSplitAmount()).isEqualByComparingTo("12.50");
            assertThat(member.getAcceptedAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 10, 2));
        });
    }
}
