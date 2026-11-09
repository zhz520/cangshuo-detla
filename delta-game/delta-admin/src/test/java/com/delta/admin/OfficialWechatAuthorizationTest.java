package com.delta.admin;

import com.delta.admin.controller.OfficialWechatController;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.OfficialWechatService;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

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
        var jdbc = mock(JdbcTemplate.class);
        var controller = new OfficialWechatController(service, jdbc, platform);
        var request = new MockHttpServletRequest();
        request.setServerName("shop.example.com");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userType", "USER", "userId", 7L), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(service.startBinding("USER", 7L, "shop.example.com")).thenReturn("https://open.weixin.qq.com/");
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        controller.start(request);
        controller.complete(Map.of("code", "code", "state", "state"), request);
        controller.notices();
        controller.retry("notice-1");

        verify(service).startBinding("USER", 7L, "shop.example.com");
        verify(service).finishBinding("USER", 7L, "code", "state", "shop.example.com");
        verifyNoInteractions(platform);
    }

    @Test void bindingServiceRequiresThePaidFeatureForTheRequestDomain() {
        var jdbc = mock(JdbcTemplate.class);
        var configs = mock(SysConfigService.class);
        var permissions = mock(StaffPermissions.class);
        var platform = mock(PlatformService.class);
        var redis = mock(StringRedisTemplate.class);
        var service = new OfficialWechatService(jdbc, configs, new com.delta.admin.service.NotificationTemplateDataService(jdbc), permissions, platform, redis);
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
        var jdbc = mock(JdbcTemplate.class);
        var platform = mock(PlatformService.class);
        var controller = new OfficialWechatController(service, jdbc, platform);
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

    private static void authenticate(String type, Long id, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userType", type, "userId", id), null,
                List.of(new SimpleGrantedAuthority(authority))));
    }
}
