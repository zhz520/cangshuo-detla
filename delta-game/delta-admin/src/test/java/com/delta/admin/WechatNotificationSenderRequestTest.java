package com.delta.admin;

import com.delta.common.config.SecurityConfig;
import com.delta.common.exception.BusinessException;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.service.TokenService;
import com.delta.notification.controller.WechatNotificationSenderController;
import com.delta.notification.service.WechatNotificationSender;
import com.delta.platform.PlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes=WechatNotificationSenderRequestTest.Config.class)
@AutoConfigureMockMvc
class WechatNotificationSenderRequestTest {
    @Configuration
    @EnableAutoConfiguration(excludeName="com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @Import({SecurityConfig.class,JwtAuthenticationFilter.class,AuthenticationEntryPointImpl.class,
            AccessDeniedHandlerImpl.class,GlobalExceptionHandler.class,WechatNotificationSenderController.class})
    static class Config {}
    static final String PATH="/admin/wechat/notification-sender";
    @Autowired MockMvc mvc;
    @MockBean TokenService tokens;
    @MockBean PlatformService platform;
    @MockBean WechatNotificationSender sender;
    @MockBean(name="staffPermissions") StaffPermissions permissions;
    RequestPostProcessor role(String type){
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId",1L,"userType",type),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+type))));
    }
    @Test void selectedAccountAndModeRoundTrip() throws Exception {
        when(permissions.allowed("system.manage")).thenReturn(true);
        var selected=new WechatNotificationSender.Selection("MANUAL","MANAGED","one","wx-app",
                List.of(new WechatNotificationSender.Candidate("one","shop","wx-app")));
        when(sender.current()).thenReturn(selected);
        var choice=new WechatNotificationSender.Choice("MANUAL","one");
        when(sender.choose(choice)).thenReturn(selected);
        mvc.perform(put(PATH).with(role("ADMIN")).contentType("application/json").content("{\"mode\":\"MANUAL\",\"accountId\":\"one\"}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.status").value("MANAGED"));
        mvc.perform(get(PATH).with(role("ADMIN"))).andExpect(jsonPath("$.data.accountId").value("one"))
                .andExpect(jsonPath("$.data.candidates[0].appId").value("wx-app"));
        verify(platform,times(2)).requireFeature("official_wechat_notifications","localhost");
        verify(sender).choose(choice);
    }
    @Test void unauthorizedRolePermissionAndUnlicensedDomainNeverReachMutation() throws Exception {
        when(permissions.allowed("system.manage")).thenReturn(false);
        mvc.perform(put(PATH).with(role("ADMIN")).contentType("application/json").content("{\"mode\":\"DISABLED\"}"))
                .andExpect(jsonPath("$.code").value(403));
        when(permissions.allowed("system.manage")).thenReturn(true);
        mvc.perform(get(PATH).with(role("CS"))).andExpect(jsonPath("$.code").value(403));
        doThrow(new BusinessException(403,"未授权")).when(platform).requireFeature("official_wechat_notifications","localhost");
        mvc.perform(put(PATH).with(role("ADMIN")).contentType("application/json").content("{\"mode\":\"DISABLED\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(sender);
    }
}
