package com.delta.marketing.controller;

import com.delta.marketing.controller.MembershipController;
import com.delta.common.membership.MembershipTierRules;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipControllerTest {
    JdbcTemplate jdbc;
    SysConfigService configs;
    MembershipController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:membership-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(20),amount DECIMAL(12,2),refund_amount DECIMAL(12,2))");
        configs = mock(SysConfigService.class);
        controller = new MembershipController(jdbc, configs);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null, List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test
    void netSpendSelectsTheHighestReachedTier() {
        jdbc.update("INSERT INTO `order` VALUES(1,7)");
        jdbc.update("INSERT INTO payment VALUES(1,1,'PAID',600,50)");
        when(configs.getConfigValue("membership.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue(eq("membership.tiers"), anyString())).thenReturn(
                "[{\"code\":\"NORMAL\",\"name\":\"普通\",\"minSpend\":0,\"discountRate\":1},{\"code\":\"SILVER\",\"name\":\"白银\",\"minSpend\":500,\"discountRate\":0.98}]");
        var result = controller.current().getData();
        assertThat(result.get("netSpend")).isEqualTo(new java.math.BigDecimal("550.00"));
        assertThat(((MembershipTierRules.Tier) result.get("tier")).code()).isEqualTo("SILVER");
    }

    @Test
    void disabledMembershipDoesNotReturnATier() {
        when(configs.getConfigValue("membership.enabled", "false")).thenReturn("false");
        var result = controller.current().getData();
        assertThat(result.get("enabled")).isEqualTo(false);
    }
}
