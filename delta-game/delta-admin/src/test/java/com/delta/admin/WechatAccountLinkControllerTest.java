package com.delta.admin;

import com.delta.admin.controller.WechatAccountLinkController;
import com.delta.common.enums.UserTypeEnum;
import com.delta.common.security.service.TokenService;
import com.delta.common.security.service.VerificationService;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WechatAccountLinkControllerTest {
    JdbcTemplate jdbc;
    UserService users;
    VerificationService verification;
    TokenService tokens;
    WechatAccountLinkController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat-link-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,openid VARCHAR(64),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE payment(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE wallet(user_id BIGINT PRIMARY KEY,balance DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE wechat_official_binding(user_type VARCHAR(16),user_id BIGINT,openid VARCHAR(128),PRIMARY KEY(user_type,user_id),UNIQUE(openid))");
        users = mock(UserService.class);
        verification = mock(VerificationService.class);
        tokens = mock(TokenService.class);
        controller = new WechatAccountLinkController(users, jdbc, verification, tokens);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 1L, "userType", "USER"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verifiedPhoneMovesTheWechatBindingToTheExistingAccount() {
        jdbc.update("INSERT INTO `user` VALUES(1,'oa-temp',1,0),(2,'h5-phone',1,0)");
        jdbc.update("INSERT INTO wechat_official_binding VALUES('USER',1,'openid-1')");
        jdbc.update("INSERT INTO wallet VALUES(1,0)");
        User temp = user(1L, "oa-temp");
        User existing = user(2L, "h5-phone");
        when(users.getById(1L)).thenReturn(temp);
        when(users.getByPhone("13800000000")).thenReturn(existing);
        when(tokens.createToken(2L, UserTypeEnum.USER)).thenReturn("existing-token");

        var result = controller.link(new WechatAccountLinkController.LinkRequest(
                "13800000000", "request-1", "123456"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getToken()).isEqualTo("existing-token");
        assertThat(jdbc.queryForObject("SELECT user_id FROM wechat_official_binding", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT deleted FROM `user` WHERE id=1", Integer.class)).isEqualTo(1);
        verify(verification).verify("13800000000", "BIND_NEW", "1", "request-1", "123456");
    }

    @Test
    void accountWithBusinessDataIsNeverMergedAutomatically() {
        jdbc.update("INSERT INTO `user` VALUES(1,'oa-temp',1,0),(2,'h5-phone',1,0)");
        jdbc.update("INSERT INTO wechat_official_binding VALUES('USER',1,'openid-1')");
        jdbc.update("INSERT INTO wallet VALUES(1,0)");
        jdbc.update("INSERT INTO `order` VALUES(10,1)");
        when(users.getById(1L)).thenReturn(user(1L, "oa-temp"));
        when(users.getByPhone("13800000000")).thenReturn(user(2L, "h5-phone"));

        var result = controller.link(new WechatAccountLinkController.LinkRequest(
                "13800000000", "request-1", "123456"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT user_id FROM wechat_official_binding", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT deleted FROM `user` WHERE id=1", Integer.class)).isZero();
        verifyNoInteractions(tokens);
    }

    private User user(long id, String openid) {
        User user = new User();
        user.setId(id); user.setOpenid(openid); user.setStatus(1);
        return user;
    }
}
