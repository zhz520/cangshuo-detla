package com.delta.marketing.controller;

import com.delta.marketing.controller.CouponController;
import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponControllerTest {
    JdbcTemplate jdbc; SysConfigService configs; CouponController controller;
    @BeforeEach void setup(){
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:coupon-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE coupon_template(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(80),type VARCHAR(16),coupon_value DECIMAL(12,2),min_spend DECIMAL(12,2),valid_days INT,total INT,claimed INT,per_user_limit INT,status TINYINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE user_coupon(id BIGINT AUTO_INCREMENT PRIMARY KEY,template_id BIGINT,user_id BIGINT,status VARCHAR(16),claimed_at TIMESTAMP,expires_at TIMESTAMP,used_order_id BIGINT,used_at TIMESTAMP)");
        configs=mock(SysConfigService.class);controller=new CouponController(jdbc,configs);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Map.of("userId",7L,"userType","USER"),null,List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void stockAndPerUserLimitAreEnforced(){
        jdbc.update("INSERT INTO coupon_template(name,type,coupon_value,min_spend,valid_days,total,claimed,per_user_limit,status) VALUES('测试券','AMOUNT',10,0,7,1,0,1,1)");
        when(configs.getConfigValue("marketing.coupon.issue_enabled","false")).thenReturn("true");
        controller.claim(1L);
        assertThat(jdbc.queryForObject("SELECT claimed FROM coupon_template WHERE id=1",Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> controller.claim(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("已领完");
    }

    @Test void disabledIssueSwitchReturnsNoCoupons(){
        when(configs.getConfigValue("marketing.coupon.issue_enabled","false")).thenReturn("false");
        assertThat(controller.available().getData()).isEmpty();
        assertThatThrownBy(() -> controller.claim(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("已关闭");
    }

    @Test void perUserLimitIsEnforcedWhenStockIsUnlimited(){
        jdbc.update("INSERT INTO coupon_template(name,type,coupon_value,min_spend,valid_days,total,claimed,per_user_limit,status) VALUES('限领券','AMOUNT',10,0,7,0,0,1,1)");
        when(configs.getConfigValue("marketing.coupon.issue_enabled","false")).thenReturn("true");
        controller.claim(1L);
        assertThatThrownBy(() -> controller.claim(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("已领取");
    }

    @Test void perUserLimitGreaterThanOneAllowsMultipleRows(){
        jdbc.update("INSERT INTO coupon_template(name,type,coupon_value,min_spend,valid_days,total,claimed,per_user_limit,status) VALUES('可领两张','AMOUNT',10,0,7,0,0,2,1)");
        when(configs.getConfigValue("marketing.coupon.issue_enabled","false")).thenReturn("true");

        controller.claim(1L);
        controller.claim(1L);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE user_id=7 AND template_id=1", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> controller.claim(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("已领取");
    }
}
