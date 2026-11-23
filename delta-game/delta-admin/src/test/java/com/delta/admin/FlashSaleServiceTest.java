package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.order.service.FlashSaleService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlashSaleServiceTest {
    private JdbcTemplate jdbc;
    private FlashSaleService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:flash_sale;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS flash_sale_claim");
        jdbc.execute("DROP TABLE IF EXISTS flash_sale_activity");
        jdbc.execute("DROP TABLE IF EXISTS `order`");
        jdbc.execute("DROP TABLE IF EXISTS product");
        jdbc.execute("CREATE TABLE product(id BIGINT PRIMARY KEY,name VARCHAR(100),status INT,deleted INT DEFAULT 0,price DECIMAL(12,2))");
        jdbc.update("INSERT INTO product(id,name,status,deleted,price) VALUES(1,'测试商品',1,0,100)");
        jdbc.execute("CREATE TABLE flash_sale_activity(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(80),product_id BIGINT,price DECIMAL(12,2)," +
                "total_stock INT,remaining_stock INT,starts_at TIMESTAMP,ends_at TIMESTAMP,status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE flash_sale_claim(id BIGINT AUTO_INCREMENT PRIMARY KEY,activity_id BIGINT,user_id BIGINT,order_id BIGINT," +
                "price DECIMAL(12,2),status VARCHAR(16),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(activity_id,user_id))");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,status VARCHAR(32),pay_deadline TIMESTAMP)");
        service = new FlashSaleService(jdbc);
    }

    @Test
    void reservesOnceAndPersistsFlashPrice() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> created = service.save(null, Map.of(
                "name", "秒杀测试", "productId", 1, "price", "9.90", "stock", 2,
                "startsAt", now.minusMinutes(1).withNano(0).toString(), "endsAt", now.plusHours(1).withNano(0).toString(), "enabled", true));
        long id = ((Number) created.get("id")).longValue();
        assertThat(service.activePrice(1L, 7L)).isEqualByComparingTo(new BigDecimal("9.90"));
        var reservation = service.reserveIfActive(7L, 1L);
        service.attachOrder(reservation, 99L);
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM flash_sale_activity WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThatThrownBy(() -> service.reserveIfActive(7L, 1L)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(409));
    }
}
