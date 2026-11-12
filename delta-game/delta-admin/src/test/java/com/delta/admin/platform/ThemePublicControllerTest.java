package com.delta.admin.platform;

import com.delta.system.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThemePublicControllerTest {
    @Mock PlatformService platform;
    @Mock SysConfigService configs;
    @InjectMocks ThemePublicController controller;

    @Test
    void fallsBackToDefaultWhenThePaidThemeIsNotEnabled() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn("shop.example.com");
        when(configs.getConfigValue(ThemeController.ACTIVE_THEME_KEY, ThemeController.DEFAULT_THEME))
                .thenReturn(ThemeController.NEUMORPHISM_THEME);
        when(platform.featureEnabled(ThemeController.THEME_FEATURE, "shop.example.com")).thenReturn(false);

        assertThat(controller.theme(request).getData().get("id")).isEqualTo(ThemeController.DEFAULT_THEME);
    }

    @Test
    void returnsNeumorphismOnlyForAnEnabledThemeOnTheRequestedDomain() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn("shop.example.com");
        when(configs.getConfigValue(ThemeController.ACTIVE_THEME_KEY, ThemeController.DEFAULT_THEME))
                .thenReturn(ThemeController.NEUMORPHISM_THEME);
        when(platform.featureEnabled(ThemeController.THEME_FEATURE, "shop.example.com")).thenReturn(true);

        assertThat(controller.theme(request).getData().get("id")).isEqualTo(ThemeController.NEUMORPHISM_THEME);
    }
}
