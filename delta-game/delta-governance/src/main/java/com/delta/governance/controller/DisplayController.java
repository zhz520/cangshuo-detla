package com.delta.governance.controller;

import com.delta.common.domain.R;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/app/display")
@RequiredArgsConstructor
public class DisplayController {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    @GetMapping("/spend-ranking")
    public R<Map<String, Object>> spending(@RequestParam(defaultValue = "20") int limit) {
        if (!enabled("display.spend_rank.enabled")) return R.ok(disabled());
        int size = Math.max(1, Math.min(limit, 50));
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id,u.nickname,u.avatar,SUM(COALESCE(p.amount,0)-COALESCE(p.refund_amount,0)) spend " +
                        "FROM payment p JOIN `order` o ON o.id=p.order_id JOIN `user` u ON u.id=o.user_id " +
                        "LEFT JOIN display_privacy dp ON dp.user_id=u.id " +
                        "WHERE p.status IN ('PAID','REFUNDED') AND p.paid_at>=? AND COALESCE(u.is_guest,0)=0 " +
                        "AND COALESCE(dp.spend_rank_visible,1)=1 " +
                        "GROUP BY u.id,u.nickname,u.avatar HAVING spend>0 ORDER BY spend DESC,u.id ASC LIMIT ?",
                start, size);
        for (Map<String, Object> row : rows) {
            row.put("nickname", mask(String.valueOf(row.get("nickname"))));
            row.remove("id");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("period", "MONTH");
        result.put("items", rows);
        return R.ok(result);
    }

    @GetMapping("/purchase-feed")
    public R<Map<String, Object>> feed(@RequestParam(defaultValue = "20") int limit) {
        if (!enabled("display.purchase_feed.enabled")) return R.ok(disabled());
        int size = Math.max(1, Math.min(limit, 30));
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT u.nickname,o.product_name FROM `order` o JOIN `user` u ON u.id=o.user_id " +
                        "JOIN display_privacy dp ON dp.user_id=u.id AND dp.purchase_feed_consent=1 " +
                        "WHERE COALESCE(u.deleted,0)=0 AND o.status IN ('PAID','ASSIGNED','ACCEPTED','WAITING_TEAMMATE','IN_PROGRESS','COMPLETED','CONFIRMED','REVIEWED') " +
                        "AND COALESCE(u.is_guest,0)=0 AND o.created_at>=? ORDER BY o.created_at DESC LIMIT ?",
                (row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("nickname", mask(row.getString(1)));
                    item.put("product_name", row.getString(2));
                    return item;
                }, since, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("items", rows);
        return R.ok(result);
    }

    private boolean enabled(String key) {
        return "true".equalsIgnoreCase(configs.getConfigValue(key, "false"));
    }

    private Map<String, Object> disabled() {
        return Map.of("enabled", false, "items", List.of());
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) return "匿名玩家";
        int length = value.codePointCount(0, value.length());
        if (length <= 1) return value + "**";
        return value.substring(0, value.offsetByCodePoints(0, 1)) + "**";
    }
}
