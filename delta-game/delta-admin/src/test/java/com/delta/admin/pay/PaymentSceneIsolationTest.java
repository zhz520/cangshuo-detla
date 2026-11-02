package com.delta.admin.pay;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.pay.entity.Payment;
import com.delta.pay.service.TransactionService;
import com.delta.pay.service.WechatPayScene;
import com.delta.pay.service.impl.PaymentServiceImpl;
import com.delta.product.service.ProductService;
import com.delta.system.service.SysConfigService;
import com.delta.user.service.WalletService;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentSceneIsolationTest {
    @Test
    void differentWechatScenesNeverReuseTheSameOutTradeNo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Payment.class);
        TransactionService transactions = mock(TransactionService.class);
        OrderService orders = mock(OrderService.class);
        OrderProgressService progress = mock(OrderProgressService.class);
        WalletService wallets = mock(WalletService.class);
        ProductService products = mock(ProductService.class);
        SysConfigService configs = mock(SysConfigService.class);
        WxPayConfiguration wx = mock(WxPayConfiguration.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentServiceImpl service = spy(new PaymentServiceImpl(transactions, orders, progress, wallets, products,
                configs, wx, redis, events, jdbc));

        Order order = new Order();
        order.setId(100L);
        order.setUserId(7L);
        order.setStatus(OrderStatusEnum.PENDING_PAYMENT.name());
        order.setAmount(new BigDecimal("88.00"));
        order.setPayDeadline(LocalDateTime.now().plusMinutes(20));
        when(orders.getById(100L)).thenReturn(order);
        doReturn(null).when(service).getOne(any());
        doReturn(true).when(service).save(any(Payment.class));

        Payment mini = service.createWxPayment(100L, 7L, WechatPayScene.MINIAPP, "wx-mini");
        Payment official = service.createWxPayment(100L, 7L, WechatPayScene.OFFICIAL_ACCOUNT, "wx-official");

        assertThat(mini.getPaymentNo()).isNotEqualTo(official.getPaymentNo());
        assertThat(mini.getPayMethod()).isEqualTo("WECHAT_MINIAPP");
        assertThat(mini.getWxAppId()).isEqualTo("wx-mini");
        assertThat(official.getPayMethod()).isEqualTo("WECHAT_OFFICIAL");
        assertThat(official.getWxAppId()).isEqualTo("wx-official");

        ArgumentCaptor<Wrapper<Payment>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(service, times(4)).getOne(queries.capture());
        @SuppressWarnings("unchecked")
        LambdaQueryWrapper<Payment> officialQuery = (LambdaQueryWrapper<Payment>) queries.getAllValues().get(3);
        assertThat(officialQuery.getSqlSegment()).contains("pay_method", "wx_app_id", "status");
    }
}
