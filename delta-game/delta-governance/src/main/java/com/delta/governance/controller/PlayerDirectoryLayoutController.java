package com.delta.governance.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.governance.service.PlayerDirectoryLayoutService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PlayerDirectoryLayoutController {
    public static final String FEATURE_ID = "player_directory_layouts";

    private final PlatformService platform;
    private final PlayerDirectoryLayoutService layouts;

    @GetMapping("/app/players/layout")
    public R<Map<String, Object>> publicLayout(HttpServletRequest request) {
        boolean licensed = platform.featureEnabled(FEATURE_ID, request.getServerName());
        return R.ok(Map.of("layout", licensed ? layouts.currentLayout() : PlayerDirectoryLayoutService.DEFAULT_LAYOUT,
                "licensed", licensed));
    }

    @GetMapping("/admin/player-layout")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> settings(HttpServletRequest request) {
        platform.requireFeature(FEATURE_ID, request.getServerName());
        return R.ok(layouts.settings());
    }

    @PutMapping("/admin/player-layout")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "player-layout", operation = "修改打手列表布局")
    public R<Map<String, Object>> save(@RequestBody LayoutRequest body, HttpServletRequest request) {
        platform.requireFeature(FEATURE_ID, request.getServerName());
        return R.ok(layouts.save(body == null ? null : body.layout()));
    }

    public record LayoutRequest(String layout) {}
}
