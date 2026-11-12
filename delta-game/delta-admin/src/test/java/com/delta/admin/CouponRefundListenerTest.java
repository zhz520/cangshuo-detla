package com.delta.admin;

import com.delta.admin.listener.CouponRefundListener;
import com.delta.common.event.BusinessEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CouponRefundListenerTest {
    JdbcTemplate jdbc; CouponRefundListener listener;
    @BeforeEach void setup(){
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:coupon-refund-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE payment(order_id BIGINT,status VARCHAR(20),refund_amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE user_coupon(id BIGINT PRIMARY KEY,status VARCHAR(16),used_order_id BIGINT,used_at TIMESTAMP,expires_at TIMESTAMP)");
        jdbc.update("INSERT INTO `order` VALUES(100,100.00)");
        jdbc.update("INSERT INTO user_coupon VALUES(1,'USED',100,CURRENT_TIMESTAMP,DATEADD('DAY',7,CURRENT_TIMESTAMP))");
        listener=new CouponRefundListener(jdbc);
    }

    @Test void fullRefundReturnsTheCoupon(){
        jdbc.update("INSERT INTO payment VALUES(100,'REFUNDED',100.00)");
        listener.onOrderRefunded(new BusinessEvent(this,"ORDER_REFUNDED","USER",7L,100L,"退款"));
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=1",String.class)).isEqualTo("AVAILABLE");
    }

    @Test void partialRefundKeepsTheCouponUsed(){
        jdbc.update("INSERT INTO payment VALUES(100,'REFUNDED',50.00)");
        listener.onOrderRefunded(new BusinessEvent(this,"ORDER_REFUNDED","USER",7L,100L,"部分退款"));
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=1",String.class)).isEqualTo("USED");
    }

    @Test void fullRefundDoesNotResurrectAnExpiredCoupon(){
        jdbc.update("UPDATE user_coupon SET expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=1");
        jdbc.update("INSERT INTO payment VALUES(100,'REFUNDED',100.00)");
        listener.onOrderRefunded(new BusinessEvent(this,"ORDER_REFUNDED","USER",7L,100L,"退款"));
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=1",String.class)).isEqualTo("EXPIRED");
    }
}
