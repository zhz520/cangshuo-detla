package com.delta.governance.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores and validates the licensed player-directory layout. */
@Service
@RequiredArgsConstructor
public class PlayerDirectoryLayoutService {
    public static final String CONFIG_KEY = "player.directory.layout";
    public static final String DEFAULT_LAYOUT = "grid";
    private static final Set<String> LAYOUTS = Set.of("grid", "list", "compact");
    private static final List<Map<String, String>> OPTIONS = List.of(
            option("grid", "图文卡片", "默认三列卡片，突出封面、等级和在线状态。"),
            option("list", "详情列表", "大图横向卡片，展示简介、标签、战绩和快捷操作。"),
            option("compact", "紧凑列表", "缩小图片与间距，一屏显示更多打手。"));

    private final JdbcTemplate jdbc;

    public String currentLayout() {
        String value = jdbc.query("SELECT config_value FROM sys_config WHERE config_key=? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, CONFIG_KEY);
        return value != null && LAYOUTS.contains(value) ? value : DEFAULT_LAYOUT;
    }

    public Map<String, Object> settings() {
        return Map.of("layout", currentLayout(), "options", OPTIONS);
    }

    public Map<String, Object> save(String requestedLayout) {
        String layout = requestedLayout == null ? "" : requestedLayout.trim();
        if (!LAYOUTS.contains(layout)) throw new BusinessException(400, "请选择有效的打手列表布局");
        int updated = jdbc.update("UPDATE sys_config SET config_value=?,updated_at=CURRENT_TIMESTAMP WHERE config_key=?", layout, CONFIG_KEY);
        if (updated == 0) jdbc.update("INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                CONFIG_KEY, "打手列表布局", layout, "text", "打手与审核", "功能商城启用后可选择图文卡片、详情列表或紧凑列表");
        return Map.of("layout", layout, "options", OPTIONS);
    }

    private static Map<String, String> option(String id, String name, String description) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("description", description);
        return result;
    }
}
