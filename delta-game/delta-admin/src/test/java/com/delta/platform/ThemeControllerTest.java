package com.delta.platform;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThemeControllerTest {
    @Mock PlatformService platform;
    @Mock SysConfigService configs;
    @InjectMocks ThemeController controller;

    @Test
    void storedPaidThemeFallsBackWhenTheLocalModuleIsDisabled() {
        when(configs.getConfigValue(ThemeController.ACTIVE_THEME_KEY, ThemeController.DEFAULT_THEME))
                .thenReturn(ThemeController.NEUMORPHISM_THEME);
        when(platform.features()).thenReturn(List.of(feature(true, false)));

        ThemeController.ThemeState state = controller.list().getData();

        assertThat(state.activeTheme()).isEqualTo(ThemeController.DEFAULT_THEME);
        assertThat(state.themes()).extracting(ThemeController.ThemeOption::id)
                .containsExactly(ThemeController.DEFAULT_THEME, ThemeController.NEUMORPHISM_THEME, ThemeController.DARK_THEME);
        assertThat(state.themes().get(1).entitled()).isTrue();
        assertThat(state.themes().get(1).enabled()).isFalse();
        assertThat(state.themes().get(1).active()).isFalse();
    }

    @Test
    void activationRejectsUnknownThemes() {
        assertThatThrownBy(() -> controller.activate(new ThemeController.ActivateRequest("custom"), request("shop.example.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("主题不存在");
    }

    @Test
    void activationUsesTheRequestDomainForThePaidEntitlement() {
        doThrow(new BusinessException(403, "此功能尚未获得有效授权"))
                .when(platform).requireFeature(ThemeController.THEME_FEATURE, "shop.example.com");

        assertThatThrownBy(() -> controller.activate(
                new ThemeController.ActivateRequest(ThemeController.NEUMORPHISM_THEME), request("shop.example.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未获得有效授权");

        verify(platform).requireFeature(ThemeController.THEME_FEATURE, "shop.example.com");
    }

    private MockHttpServletRequest request(String host) {
        var request = new MockHttpServletRequest();
        request.setServerName(host);
        return request;
    }

    private PlatformService.FeatureView feature(boolean entitled, boolean enabled) {
        return new PlatformService.FeatureView(
                ThemeController.THEME_FEATURE, "柔光拟态主题", "网页端主题", true, 0, 365, "1.15.0",
                List.of("core_platform"), "/platform/themes", List.of("网页端主题"), enabled ? "ENABLED" : "DISABLED",
                entitled, enabled, "测试主题", null, null, false);
    }
}
