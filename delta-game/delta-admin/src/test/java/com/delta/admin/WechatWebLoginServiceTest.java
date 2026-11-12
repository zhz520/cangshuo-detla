package com.delta.admin;

import com.delta.admin.service.OfficialWechatService;
import com.delta.admin.service.WechatWebLoginService;
import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.TokenService;
import com.delta.system.service.SysConfigService;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import com.delta.user.controller.InviteController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WechatWebLoginServiceTest {
    @Mock OfficialWechatService officialWechat;
    @Mock SysConfigService configs;
    @Mock RedisService redis;
    @Mock JdbcTemplate jdbc;
    @Mock UserService users;
    @Mock TokenService tokens;
    @Mock InviteController invites;
    @InjectMocks WechatWebLoginService service;

    @Test
    void startRequiresExplicitEnablement() {
        when(configs.getConfigValue("auth.wechat.enabled", "false")).thenReturn("false");
        assertThatThrownBy(() -> service.startUrl("/pages/login/index", "", mock(HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未启用");
    }

    @Test
    void startBuildsAnEncodedOfficialAccountCallback() {
        when(configs.getConfigValue("auth.wechat.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn("wx-public-app");
        when(configs.getConfigValue("wechat.official.app_secret", "")).thenReturn("test-secret");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getServerName()).thenReturn("shop.example.com");
        when(request.getServerPort()).thenReturn(443);

        String url = service.startUrl("/pages/login/index?from=web", "ABCD2345", request);

        assertThat(url).contains("appid=wx-public-app", "scope=snsapi_base", "state=");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fshop.example.com%2Fuser%2Fauth%2Fwechat%2Fcallback");
        verify(redis).set(startsWith("wechat:web-login:state:"), eq("/pages/login/index?from=web\nABCD2345"), eq(5L), any());
    }

    @Test
    void exchangeConsumesTheTicketAndIssuesAUserToken() {
        String ticket = "0123456789abcdef0123456789abcdef";
        when(redis.getAndDelete("wechat:web-login:ticket:" + ticket)).thenReturn("7");
        User user = new User();
        user.setId(7L); user.setStatus(1); user.setNickname("客户甲");
        when(users.getById(7L)).thenReturn(user);
        when(tokens.createToken(7L, UserTypeEnum.USER)).thenReturn("signed-token");

        var response = service.exchange(ticket);

        assertThat(response.getToken()).isEqualTo("signed-token");
        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getNickname()).isEqualTo("客户甲");
    }

    @Test
    void callbackRequiresAConfiguredCustomerH5OriginBeforeRedirecting() throws Exception {
        when(redis.getAndDelete("wechat:web-login:state:0123456789abcdef0123456789abcdef"))
                .thenReturn("/pages/login/index\n");
        when(officialWechat.exchangeOpenid("code")).thenReturn("openid-example");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(Map.of("user_id", 7L)));
        User user = new User();
        user.setId(7L); user.setStatus(1);
        when(users.getById(7L)).thenReturn(user);
        when(configs.getConfigValue("site.customer_web_url", "")).thenReturn("");

        assertThatThrownBy(() -> service.complete("code", "0123456789abcdef0123456789abcdef",
                mock(HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("客户 H5 站点地址");
    }

    @Test
    void newOfficialAccountUserReceivesTheInviteAttributionBeforeTicketExchange() throws Exception {
        String state = "0123456789abcdef0123456789abcdef";
        when(redis.getAndDelete("wechat:web-login:state:" + state)).thenReturn("/pages/login/index\nABCD2345");
        when(officialWechat.exchangeOpenid("code")).thenReturn("openid-new-user");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn("wx-public-app");
        when(configs.getConfigValue("site.customer_web_url", "")).thenReturn("https://shop.example.com");
        User user = new User();
        user.setId(9L); user.setStatus(1);
        when(users.loginOrRegister(anyString(), eq(""), eq(""))).thenReturn(user);

        String redirect = service.complete("code", state, mock(HttpServletRequest.class));

        assertThat(redirect).startsWith("https://shop.example.com/pages/login/index?wechat_ticket=");
        verify(invites).bindCode(9L, "ABCD2345");
    }
}
