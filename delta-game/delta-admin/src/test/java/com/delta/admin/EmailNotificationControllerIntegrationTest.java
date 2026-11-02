package com.delta.admin;

import com.delta.admin.controller.EmailNotificationController;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.EmailNotificationService;
import com.delta.admin.service.EmailNotificationTemplate;
import com.delta.common.config.SecurityConfig;
import com.delta.common.exception.BusinessException;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.service.TokenService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = EmailNotificationControllerIntegrationTest.Config.class)
@AutoConfigureMockMvc
class EmailNotificationControllerIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthenticationEntryPointImpl.class,
            AccessDeniedHandlerImpl.class, GlobalExceptionHandler.class, EmailNotificationController.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @MockBean TokenService tokens;
    @MockBean PlatformService platform;
    @MockBean EmailNotificationService service;
    @MockBean(name = "staffPermissions") StaffPermissions permissions;

    @Test
    void allEndpointsRequireLicensePermissionAndTheDocumentedContract() throws Exception {
        when(permissions.allowed("system.manage")).thenReturn(true);
        var view = new EmailNotificationService.Settings(true, "smtp.example.com", 465, "TLS",
                "mailer@example.com", "noreply@example.com", "运营通知", List.of("ops@example.com"),
                List.of("ORDER_PAID"), "", true, true,
                List.of(new EmailNotificationTemplate.EventDescriptor("ORDER_PAID", "订单已支付", "通知运营")));
        when(service.settings()).thenReturn(view);
        when(service.save(any())).thenReturn(view);
        when(service.test(any())).thenReturn(2);
        when(service.records(200)).thenReturn(List.of(Map.of("id", "record-1", "status", "FAILED",
                "event_type", "ORDER_PAID", "recipient", "ops@example.com")));

        mvc.perform(get("/admin/email-notifications/settings").with(role("ADMIN", 1)).with(host("admin.example.com")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.passwordConfigured").value(true))
                .andExpect(jsonPath("$.data.password").value(""))
                .andExpect(jsonPath("$.data.licensed").value(true))
                .andExpect(jsonPath("$.data.availableEvents[0].id").value("ORDER_PAID"));
        mvc.perform(put("/admin/email-notifications/settings").with(role("ADMIN", 1)).with(host("admin.example.com"))
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/admin/email-notifications/test").with(role("ADMIN", 1)).with(host("admin.example.com"))
                        .contentType("application/json").content("{\"recipients\":\"ops@example.com\"}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").value(2));
        mvc.perform(get("/admin/email-notifications/records").param("limit", "200").with(role("ADMIN", 1)).with(host("admin.example.com")))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].status").value("FAILED"));
        mvc.perform(post("/admin/email-notifications/records/record-1/retry").with(role("ADMIN", 1)).with(host("admin.example.com")))
                .andExpect(jsonPath("$.code").value(200));

        verify(platform, times(5)).requireFeature("email_notifications", "admin.example.com");
        InOrder order = inOrder(platform, service);
        order.verify(platform).requireFeature("email_notifications", "admin.example.com");
        order.verify(service).ensureDefaults();
        order.verify(service).settings();
    }

    @Test
    void customerServiceAndAdminsWithoutSystemManageAreDeniedBeforeServiceCalls() throws Exception {
        when(permissions.allowed("system.manage")).thenReturn(false);
        mvc.perform(get("/admin/email-notifications/settings").with(role("CS", 2)))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/admin/email-notifications/settings").with(role("ADMIN", 1)))
                .andExpect(jsonPath("$.code").value(403));
        verify(platform, never()).requireFeature(any(), any());
        verify(service, never()).settings();
    }

    @Test
    void unlicensedFeatureIsRejectedBeforeReadingOrChangingMailConfiguration() throws Exception {
        when(permissions.allowed("system.manage")).thenReturn(true);
        doThrow(new BusinessException(403, "此功能尚未获得有效授权")).when(platform).requireFeature("email_notifications", "admin.example.com");
        mvc.perform(get("/admin/email-notifications/settings").with(role("ADMIN", 1)).with(host("admin.example.com")))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(put("/admin/email-notifications/settings").with(role("ADMIN", 1)).with(host("admin.example.com"))
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(service, never()).settings();
        verify(service, never()).save(any());
    }

    private RequestPostProcessor role(String type, long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", type),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + type))));
    }

    private RequestPostProcessor host(String host) {
        return request -> { request.setServerName(host); return request; };
    }
}
