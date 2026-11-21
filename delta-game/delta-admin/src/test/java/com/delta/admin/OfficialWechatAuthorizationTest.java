package com.delta.admin;

import com.delta.notification.controller.OfficialWechatController;
import com.delta.platform.PlatformService;
import com.delta.notification.service.OfficialWechatService;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfficialWechatAuthorizationTest {
    @AfterEach void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test void everyHttpEntryUsesTheResolvedRequestDomain() throws Exception {
        var service = mock(OfficialWechatService.class);
        var platform = mock(PlatformService.class);
        var controller = new OfficialWechatController(service, platform);
        var request = new MockHttpServletRequest();
        request.setServerName("shop.example.com");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userType", "USER", "userId", 7L), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(service.startBinding("USER", 7L, "shop.example.com")).thenReturn("https://open.weixin.qq.com/");
        controller.start(request);
        controller.complete(Map.of("code", "code", "state", "state"), request);
        controller.notices(request);
        controller.retry("notice-1", request);

        verify(service).startBinding("USER", 7L, "shop.example.com");
        verify(service).finishBinding("USER", 7L, "code", "state", "shop.example.com");
        verify(platform, times(2)).requireFeature(OfficialWechatService.FEATURE_ID, "shop.example.com");
    }

    @Test void bindingServiceRequiresThePaidFeatureForTheRequestDomain() {
        var jdbc = mock(JdbcTemplate.class);
        var configs = mock(SysConfigService.class);
        var permissions = mock(StaffPermissions.class);
        var platform = mock(PlatformService.class);
        var redis = mock(StringRedisTemplate.class);
        var service = new OfficialWechatService(jdbc, configs, new com.delta.notification.service.NotificationTemplateDataService(jdbc), permissions, platform, redis);
        doThrow(new BusinessException(403, "domain denied"))
                .when(platform).requireFeature(OfficialWechatService.FEATURE_ID, "denied.example");

        assertThatThrownBy(() -> service.startBinding("USER", 7L, "denied.example"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.finishBinding("USER", 7L, "code", "state", "denied.example"))
                .isInstanceOf(BusinessException.class);
        verify(platform, times(2)).requireFeature(OfficialWechatService.FEATURE_ID, "denied.example");
    }

    @Test void adminAndCsBindingEndpointsKeepTheAuthenticatedRole() throws Exception {
        var service = mock(OfficialWechatService.class);
        var platform = mock(PlatformService.class);
        var controller = new OfficialWechatController(service, platform);
        var request = new MockHttpServletRequest();
        request.setServerName("staff.example.com");

        authenticate("ADMIN", 11L, "ROLE_ADMIN");
        controller.binding();
        controller.complete(Map.of("code", "admin-code", "state", "admin-state"), request);

        authenticate("CS", 22L, "ROLE_CS");
        controller.binding();
        controller.complete(Map.of("code", "cs-code", "state", "cs-state"), request);

        verify(service).binding("ADMIN", 11L);
        verify(service).finishBinding("ADMIN", 11L, "admin-code", "admin-state", "staff.example.com");
        verify(service).binding("CS", 22L);
        verify(service).finishBinding("CS", 22L, "cs-code", "cs-state", "staff.example.com");
    }

    @Test void staffAndCustomerBindingUseDifferentCallbackPages() {
        var jdbc = mock(JdbcTemplate.class);
        var configs = mock(SysConfigService.class);
        var permissions = mock(StaffPermissions.class);
        var platform = mock(PlatformService.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(configs.getConfigValue("wechat.official.enabled", "false")).thenReturn("true");
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn("wx1234567890");
        when(configs.getConfigValue("wechat.official.redirect_url", "")).thenReturn("https://shop.example.com/");
        when(configs.getConfigValue("wechat.official.staff_redirect_url", ""))
                .thenReturn("https://staff.example.com/mobile/wechat-binding");
        var service = new OfficialWechatService(jdbc, configs, new com.delta.notification.service.NotificationTemplateDataService(jdbc), permissions, platform, redis);

        assertThat(service.startBinding("USER", 7L, "shop.example.com"))
                .contains("redirect_uri=" + URLEncoder.encode("https://shop.example.com/", StandardCharsets.UTF_8));
        assertThat(service.startBinding("ADMIN", 11L, "staff.example.com"))
                .contains("redirect_uri=" + URLEncoder.encode("https://staff.example.com/h5/wechat-binding", StandardCharsets.UTF_8));
        assertThat(service.startBinding("CS", 22L, "staff.example.com"))
                .contains("redirect_uri=" + URLEncoder.encode("https://staff.example.com/h5/wechat-binding", StandardCharsets.UTF_8));
    }

    private static void authenticate(String type, Long id, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userType", type, "userId", id), null,
                List.of(new SimpleGrantedAuthority(authority))));
    }
}
