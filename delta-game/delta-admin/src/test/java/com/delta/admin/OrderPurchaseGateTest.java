package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.order.dto.CreateOrderRequest;
import com.delta.order.service.impl.OrderServiceImpl;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderPurchaseGateTest {
    @Test
    void disabledPurchaseSwitchBlocksAllNewOrdersBeforeUserLookup() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("order.purchase_enabled", "true")).thenReturn("false");
        OrderServiceImpl service = new OrderServiceImpl(
                mock(com.delta.order.service.OrderProgressService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.delta.order.service.OrderPlayerService.class),
                mock(com.delta.product.service.ProductService.class),
                mock(com.delta.common.chat.service.ChatSessionService.class),
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.delta.common.mapper.CrossModuleMapper.class),
                configs,
                mock(com.delta.common.security.service.VerificationService.class),
                mock(com.delta.order.service.OrderPricingService.class),
                mock(com.delta.order.service.CouponRedemptionService.class));

        assertThatThrownBy(() -> service.createOrder(1L, new CreateOrderRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前台购买功能已暂停");
    }
}
