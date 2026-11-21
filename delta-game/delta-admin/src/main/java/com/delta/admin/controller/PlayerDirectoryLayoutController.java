package com.delta.admin.controller;

import com.delta.platform.PlatformService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class PlayerDirectoryLayoutController {
    public static final String FEATURE_ID = "player_directory_layouts";
    public static final String CONFIG_KEY = "player.directory.layout";
    private static final String DEFAULT_LAYOUT = "grid";
    private static final Set<String> LAYOUTS = Set.of("grid", "list", "compact");
    private static final List<Map<String, String>> OPTIONS = List.of(
            option("grid", "图文卡片", "默认三列卡片，突出封面、等级和在线状态。"),
            option("list", "详情列表", "大图横向卡片，展示简介、标签、战绩和快捷操作。"),
            option("compact", "紧凑列表", "缩小图片与间距，一屏显示更多打手。"));

    private final PlatformService platform;
    private final JdbcTemplate jdbc;

    @GetMapping("/app/players/layout")
    public R<Map<String, Object>> publicLayout(HttpServletRequest request) {
        boolean licensed = platform.featureEnabled(FEATURE_ID, request.getServerName());
        return R.ok(Map.of("layout", licensed ? currentLayout() : DEFAULT_LAYOUT, "licensed", licensed));
    }

    @GetMapping("/admin/player-layout")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> settings(HttpServletRequest request) {
        platform.requireFeature(FEATURE_ID, request.getServerName());
        return R.ok(Map.of("layout", currentLayout(), "options", OPTIONS));
    }

    @PutMapping("/admin/player-layout")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "player-layout", operation = "修改打手列表布局")
    public R<Map<String, Object>> save(@RequestBody LayoutRequest body, HttpServletRequest request) {
        platform.requireFeature(FEATURE_ID, request.getServerName());
        String layout = body == null ? "" : String.valueOf(body.layout() == null ? "" : body.layout()).trim();
        if (!LAYOUTS.contains(layout)) throw new BusinessException(400, "请选择有效的打手列表布局");
        int updated = jdbc.update("UPDATE sys_config SET config_value=?,updated_at=CURRENT_TIMESTAMP WHERE config_key=?", layout, CONFIG_KEY);
        if (updated == 0) jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                CONFIG_KEY, "打手列表布局", layout, "text", "打手与审核", "功能商城启用后可选择图文卡片、详情列表或紧凑列表");
        return R.ok(Map.of("layout", layout, "options", OPTIONS));
    }

    private String currentLayout() {
        String value = jdbc.query("SELECT config_value FROM sys_config WHERE config_key=? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, CONFIG_KEY);
        return value != null && LAYOUTS.contains(value) ? value : DEFAULT_LAYOUT;
    }

    private static Map<String, String> option(String id, String name, String description) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("description", description);
        return result;
    }

    public record LayoutRequest(String layout) {}
}
