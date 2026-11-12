package com.delta.admin;

import com.delta.order.service.CouponRedemptionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CouponRedemptionServiceTest {
    @Test
    void quoteLockReleaseAndConsumeFollowTheCouponLifecycle() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:coupon-state-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE coupon_template(id BIGINT PRIMARY KEY,name VARCHAR(80),type VARCHAR(16),coupon_value DECIMAL(12,2),min_spend DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE user_coupon(id BIGINT PRIMARY KEY,template_id BIGINT,user_id BIGINT,status VARCHAR(16),used_order_id BIGINT,expires_at TIMESTAMP,used_at TIMESTAMP)");
        jdbc.update("INSERT INTO coupon_template VALUES(1,'满10减5','AMOUNT',5,10)");
        jdbc.update("INSERT INTO user_coupon VALUES(11,1,7,'AVAILABLE',NULL,DATEADD('DAY',7,CURRENT_TIMESTAMP),NULL)");
        CouponRedemptionService service = new CouponRedemptionService(jdbc);

        var quote = service.quote(7L, 11L, new BigDecimal("100.00"));
        assertThat(quote.discount()).isEqualByComparingTo("5.00");
        service.lock(7L, 11L, 100L);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=11", String.class)).isEqualTo("LOCKED");
        service.release(100L);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=11", String.class)).isEqualTo("AVAILABLE");
        service.lock(7L, 11L, 100L);
        service.consume(100L);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=11", String.class)).isEqualTo("USED");
    }
}
