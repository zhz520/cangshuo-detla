package com.delta.admin.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/** Customer-facing theme store; theme ownership is a paid platform entitlement. */
@RestController
@RequestMapping("/admin/platform/themes")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class ThemeController {
    public static final String DEFAULT_THEME = "default";
    public static final String NEUMORPHISM_THEME = "neumorphism";
    public static final String ACTIVE_THEME_KEY = "theme.active_id";
    public static final String THEME_FEATURE = "theme_neumorphism";
    private static final List<String> SUPPORTED = List.of(DEFAULT_THEME, NEUMORPHISM_THEME);

    private final PlatformService platform;
    private final SysConfigService configs;

    public record ThemeOption(String id, String name, String description, boolean active,
                              boolean entitled, boolean enabled, String reason) {}
    public record ThemeState(String activeTheme, List<ThemeOption> themes) {}
    public record ActivateRequest(@NotBlank @Size(max = 32) String themeId) {}

    @GetMapping
    public R<ThemeState> list() {
        return R.ok(state());
    }

    @PutMapping("/active")
    @OpLog(module = "platform", operation = "切换客户主题")
    public R<ThemeState> activate(@Valid @RequestBody ActivateRequest body) {
        String themeId = body.themeId().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(themeId)) throw new BusinessException(400, "主题不存在或暂不支持");
        if (!DEFAULT_THEME.equals(themeId)) platform.requireFeature(THEME_FEATURE);
        save(ACTIVE_THEME_KEY, themeId);
        return R.ok(state());
    }

    private ThemeState state() {
        PlatformService.FeatureView theme = platform.features().stream()
                .filter(item -> THEME_FEATURE.equals(item.id())).findFirst().orElse(null);
        String stored = configs.getConfigValue(ACTIVE_THEME_KEY, DEFAULT_THEME);
        String active = stored;
        if (!DEFAULT_THEME.equals(active) && (theme == null || !theme.enabled())) active = DEFAULT_THEME;

        ThemeOption classic = new ThemeOption(DEFAULT_THEME, "经典清爽", "保留当前浅色、清晰的业务界面。",
                DEFAULT_THEME.equals(active), true, true, "默认主题始终可用");
        ThemeOption neumorphism = new ThemeOption(NEUMORPHISM_THEME, "柔光拟态", "柔和表面、轻阴影与清晰主操作区的网页端主题。",
                NEUMORPHISM_THEME.equals(active), theme != null && theme.entitled(),
                theme != null && theme.enabled(), theme == null ? "当前版本未包含主题模块" : theme.reason());
        return new ThemeState(active, List.of(classic, neumorphism));
    }

    private void save(String key, String value) {
        SysConfig current = configs.getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (current == null) {
            SysConfig created = new SysConfig();
            created.setConfigKey(key);
            created.setConfigName("当前客户主题");
            created.setConfigValue(value);
            created.setValueType("text");
            created.setConfigGroup("主题商店");
            created.setRemark("由主题商店保存；授权失效时业务接口自动回退默认主题");
            configs.save(created);
            configs.evictCache(key);
            return;
        }
        current.setConfigValue(value);
        configs.updateById(current);
        configs.evictCache(key);
    }
}
