package com.delta.admin;

import com.delta.common.config.SecurityConfig;
import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.RateLimitService;
import com.delta.common.security.service.TokenService;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.system.service.SysConfigService;
import com.delta.user.controller.GuestCheckoutController;
import com.delta.user.service.GuestCheckoutService;
import com.delta.user.service.impl.UserServiceImpl;
import com.delta.user.service.impl.WalletServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = GuestCheckoutIntegrationTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:guestcheckout;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.main.allow-bean-definition-overriding=true",
        "jwt.secret=test-only-key-012345678901234567890123456789012345678901234567890123456789",
        "jwt.expiration=3600000",
        "identity.hash-pepper=test-guest-checkout-pepper-0123456789"
})
@AutoConfigureMockMvc
@Sql(scripts = "/guest-checkout-schema.sql")
class GuestCheckoutIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @MapperScan({"com.delta.user.mapper", "com.delta.common.mapper"})
    @Import({UserServiceImpl.class, WalletServiceImpl.class, GuestCheckoutService.class,
            GuestCheckoutController.class, TokenService.class, JwtAuthenticationFilter.class,
            SecurityConfig.class, AuthenticationEntryPointImpl.class, AccessDeniedHandlerImpl.class,
            GlobalExceptionHandler.class, com.delta.common.mybatis.handler.MetaObjectHandlerImpl.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    @MockBean RedisService redis;
    @MockBean RateLimitService limits;
    @MockBean SysConfigService configs;
    @MockBean OrderService orders;
    final Map<String,Object> sessions = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        doAnswer(a -> {
            sessions.put(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(redis).set(any(String.class), any(), anyLong(), any(TimeUnit.class));
        when(redis.get(any(String.class))).thenAnswer(a -> sessions.get(a.getArgument(0)));
        when(redis.delete(any(String.class))).thenAnswer(a -> sessions.remove(a.getArgument(0)) != null);
        when(configs.getConfigValue("order.guest_checkout_enabled", "false")).thenReturn("true");
        when(orders.createOrder(anyLong(), any())).thenAnswer(a -> {
            Order order = new Order();
            order.setId(900L);
            order.setUserId(a.getArgument(0));
            order.setAmount(new BigDecimal("99.00"));
            return order;
        });
    }

    @Test
    void createsAnUnlinkedShadowUserAndAllowsLookup() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "phone", "13900001234",
                "lookupCode", "A1B2C3",
                "privacyAccepted", true,
                "order", Map.of("productId", 11, "amount", new BigDecimal("99.00"))));
        mvc.perform(post("/guest/orders").contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.orderId").value(900))
                .andExpect(jsonPath("$.data.maskedPhone").value("139****1234"));

        Map<String,Object> guest = jdbc.queryForMap("SELECT id,openid,nickname,phone,is_guest FROM `user`");
        assertThat(((Number) guest.get("id")).longValue()).isPositive();
        assertThat(guest.get("phone")).isEqualTo("");
        assertThat(((Number) guest.get("is_guest")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(guest.get("openid"))).startsWith("guest:");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT LENGTH(phone_hash) FROM guest_checkout_profile", Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT phone_mask FROM guest_checkout_profile", String.class)).isEqualTo("139****1234");

        String lookup = json.writeValueAsString(Map.of("phone", "13900001234", "lookupCode", "a1b2c3"));
        String response = mvc.perform(post("/guest/lookup").contentType("application/json").content(lookup))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(response).path("data").path("token").asText();
        assertThat(tokens.authenticate(token)).containsEntry("userType", "GUEST");
    }

    @Test
    void completedGuestAccountCanBeLinkedWithoutChangingItsUserId() throws Exception {
        String phone = "13900001234";
        jdbc.update("INSERT INTO `user`(openid,nickname,phone,is_guest,status,deleted) VALUES(?,?,?,0,1,0)",
                "member-openid", "正式用户", phone);
        long registeredId = jdbc.queryForObject("SELECT id FROM `user` WHERE openid='member-openid'", Long.class);
        String guestBody = json.writeValueAsString(Map.of(
                "phone", phone, "lookupCode", "A1B2C3", "privacyAccepted", true,
                "order", Map.of("productId", 11, "amount", new BigDecimal("99.00"))));
        mvc.perform(post("/guest/orders").contentType("application/json").content(guestBody))
                .andExpect(jsonPath("$.code").value(200));
        long guestId = jdbc.queryForObject("SELECT user_id FROM guest_checkout_profile", Long.class);
        jdbc.update("INSERT INTO `order`(user_id,status) VALUES(?, 'PENDING_PAYMENT')", guestId);
        String token = tokens.createToken(registeredId, UserTypeEnum.USER);

        String bind = json.writeValueAsString(Map.of("phone", phone, "lookupCode", "A1B2C3"));
        mvc.perform(post("/guest/bind").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(bind))
                .andExpect(jsonPath("$.code").value(409));
        jdbc.update("UPDATE `order` SET status='COMPLETED' WHERE user_id=?", guestId);
        mvc.perform(post("/guest/bind").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(bind))
                .andExpect(jsonPath("$.code").value(409));
        jdbc.update("UPDATE `order` SET status='CONFIRMED' WHERE user_id=?", guestId);
        mvc.perform(post("/guest/bind").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(bind))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(jdbc.queryForObject("SELECT user_id FROM guest_checkout_profile", Long.class)).isEqualTo(guestId);
        assertThat(jdbc.queryForObject("SELECT registered_user_id FROM guest_user_link WHERE guest_user_id=?", Long.class, guestId))
                .isEqualTo(registeredId);
    }
}
