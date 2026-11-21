package com.delta.marketing.controller;

import com.delta.marketing.controller.CouponAdminController;
import com.delta.marketing.service.CouponAdminService;
import com.delta.common.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponAdminControllerTest {
    JdbcTemplate jdbc;
    CouponAdminController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:coupon-admin-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE coupon_template(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(80),type VARCHAR(16),
                  coupon_value DECIMAL(12,2),min_spend DECIMAL(12,2),valid_days INT,total INT,
                  claimed INT,per_user_limit INT,status TINYINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.update("INSERT INTO coupon_template(name,type,coupon_value,min_spend,valid_days,total,claimed,per_user_limit,status) VALUES('满100减10','AMOUNT',10,100,7,100,1,1,1)");
        controller = new CouponAdminController(new CouponAdminService(jdbc));
    }

    @Test
    void claimedCouponCannotChangeTypeValueOrMinimumSpend() {
        assertThatThrownBy(() -> controller.update(1L, Map.of(
                "name", "满100减10", "type", "AMOUNT", "value", 20, "minSpend", 100,
                "validDays", 7, "total", 100, "perUserLimit", 1, "status", 1)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> controller.update(1L, Map.of(
                "name", "满100减10", "type", "DISCOUNT", "value", 0.9, "minSpend", 100,
                "validDays", 7, "total", 100, "perUserLimit", 1, "status", 1)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT coupon_value FROM coupon_template WHERE id=1", java.math.BigDecimal.class))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void claimedCouponStillAllowsNameStatusTotalAndFutureClaimLimitChanges() {
        assertThat(controller.update(1L, Map.of(
                "name", "满100减10（活动调整）", "type", "AMOUNT", "value", 10, "minSpend", 100,
                "validDays", 14, "total", 200, "perUserLimit", 2, "status", 0)).getCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT name FROM coupon_template WHERE id=1", String.class))
                .isEqualTo("满100减10（活动调整）");
        assertThat(jdbc.queryForObject("SELECT total FROM coupon_template WHERE id=1", Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM coupon_template WHERE id=1", Integer.class)).isZero();
    }
}
