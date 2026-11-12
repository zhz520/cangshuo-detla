package com.delta.admin;

import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.security.service.RateLimitService;
import com.delta.common.security.service.TokenService;
import com.delta.common.security.service.VerificationService;
import com.delta.common.tenant.TenantWxMaServiceProvider;
import com.delta.system.service.SysConfigService;
import com.delta.user.controller.InviteController;
import com.delta.user.controller.UserAuthController;
import com.delta.user.dto.H5LoginRequest;
import com.delta.user.dto.PasswordLoginRequest;
import com.delta.user.service.UserPasswordService;
import com.delta.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserAuthPolicyGateTest {
    @Test
    void disabledPhoneLoginRejectsSmsAndH5LoginOnTheServer() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("auth.phone.enabled", "true")).thenReturn("false");
        VerificationService verification = mock(VerificationService.class);
        UserAuthController controller = controller(configs, verification);
        H5LoginRequest login = new H5LoginRequest();
        login.setPhone("13800138000"); login.setCode("123456"); login.setRequestId("request-1"); login.setPrivacyAccepted(true);

        assertThat(controller.smsCode(Map.of("phone", "13800138000"), mock(HttpServletRequest.class)).getCode()).isEqualTo(403);
        assertThat(controller.h5Login(login).getCode()).isEqualTo(403);
        verifyNoInteractions(verification);
    }

    @Test
    void disabledPasswordLoginRejectsPasswordEndpointBeforeCredentialChecks() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue("auth.password.enabled", "true")).thenReturn("false");
        RateLimitService limits = mock(RateLimitService.class);
        VerificationService verification = mock(VerificationService.class);
        UserAuthController controller = controller(configs, verification, limits);
        PasswordLoginRequest login = new PasswordLoginRequest();
        login.setPhone("13800138000"); login.setPassword("password123"); login.setCaptchaId("captcha"); login.setCaptchaCode("123456");

        assertThat(controller.passwordLogin(login, mock(HttpServletRequest.class)).getCode()).isEqualTo(403);
        verifyNoInteractions(limits, verification);
    }

    private UserAuthController controller(SysConfigService configs, VerificationService verification) {
        return controller(configs, verification, mock(RateLimitService.class));
    }

    @SuppressWarnings("unchecked")
    private UserAuthController controller(SysConfigService configs, VerificationService verification, RateLimitService limits) {
        ObjectProvider<TenantWxMaServiceProvider> provider = mock(ObjectProvider.class);
        return new UserAuthController(provider, mock(UserService.class), mock(TokenService.class),
                mock(CrossModuleMapper.class), verification, limits, mock(UserPasswordService.class),
                mock(InviteController.class), configs, mock(PasswordEncoder.class));
    }
}
