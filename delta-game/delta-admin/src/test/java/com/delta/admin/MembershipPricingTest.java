package com.delta.admin;

import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.service.OrderPricingService;
import com.delta.product.entity.Product;
import com.delta.product.service.ProductService;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MembershipPricingTest {
    @Test
    void membershipDiscountIsAppliedAndSnapshotted() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pricing-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(20),amount DECIMAL(12,2),refund_amount DECIMAL(12,2))");
        jdbc.update("INSERT INTO `order` VALUES(1,7)");
        jdbc.update("INSERT INTO payment VALUES(1,1,'PAID',600,50)");
        ProductService products = mock(ProductService.class);
        Product product = new Product();
        product.setId(11L); product.setName("测试商品"); product.setStatus(1); product.setPrice(new BigDecimal("100.00"));
        when(products.getById(11L)).thenReturn(product);
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("membership.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue(eq("membership.tiers"), anyString())).thenReturn(
                "[{\"code\":\"NORMAL\",\"name\":\"普通\",\"minSpend\":0,\"discountRate\":1},{\"code\":\"SILVER\",\"name\":\"白银\",\"minSpend\":500,\"discountRate\":0.98}]");
        OrderPricingService pricing = new OrderPricingService(products, mock(CrossModuleMapper.class), configs, jdbc);

        var quote = pricing.quote(11L, null, null, 7L);

        assertThat(quote.getAmount()).isEqualByComparingTo("98.00");
        assertThat(quote.getMemberTier()).isEqualTo("白银");
        assertThat(quote.getMemberDiscountRate()).isEqualByComparingTo("0.98");
        assertThat(quote.getMemberDiscountAmount()).isEqualByComparingTo("2.00");
    }
}
