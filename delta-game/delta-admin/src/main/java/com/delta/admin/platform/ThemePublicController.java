package com.delta.admin.platform;

import com.delta.common.domain.R;
import com.delta.system.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public, non-sensitive theme descriptor consumed by customer and player H5 pages. */
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class ThemePublicController {
    private final PlatformService platform;
    private final SysConfigService configs;

    @GetMapping("/theme")
    public R<Map<String, String>> theme(HttpServletRequest request) {
        String stored = configs.getConfigValue(ThemeController.ACTIVE_THEME_KEY, ThemeController.DEFAULT_THEME);
        String active = ThemeController.DEFAULT_THEME;
        if (ThemeController.NEUMORPHISM_THEME.equals(stored)
                && platform.featureEnabled(ThemeController.THEME_FEATURE, request.getServerName())) {
            active = ThemeController.NEUMORPHISM_THEME;
        }
        return R.ok(Map.of("id", active, "version", "1"));
    }
}
