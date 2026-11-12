package com.delta.admin.controller;

import com.alibaba.fastjson2.JSON;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/user/membership")
@RequiredArgsConstructor
public class MembershipController {
    private static final String DEFAULT_TIERS = "[{\"code\":\"NORMAL\",\"name\":\"普通会员\",\"minSpend\":0,\"discountRate\":1},{\"code\":\"SILVER\",\"name\":\"白银会员\",\"minSpend\":500,\"discountRate\":0.98},{\"code\":\"GOLD\",\"name\":\"黄金会员\",\"minSpend\":2000,\"discountRate\":0.95}]";
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    @GetMapping
    public R<Map<String, Object>> current() {
        if (!"true".equalsIgnoreCase(configs.getConfigValue("membership.enabled", "false")))
            return R.ok(Map.of("enabled", false, "tier", Map.of(), "netSpend", BigDecimal.ZERO));
        Long userId = SecurityUtils.getUserId();
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(COALESCE(p.amount,0)-COALESCE(p.refund_amount,0)),0) FROM payment p " +
                        "JOIN `order` o ON o.id=p.order_id WHERE o.user_id=? AND p.status IN ('PAID','REFUNDED')",
                BigDecimal.class, userId);
        if (net == null) net = BigDecimal.ZERO;
        List<Tier> tiers = parseTiers();
        Tier current = tiers.get(0), next = null;
        for (Tier tier : tiers) {
            if (net.compareTo(tier.minSpend()) >= 0) current = tier;
            else if (next == null) next = tier;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("netSpend", net);
        result.put("tier", current);
        result.put("nextTier", next);
        result.put("nextThreshold", next == null ? null : next.minSpend());
        return R.ok(result);
    }

    private List<Tier> parseTiers() {
        try {
            List<Tier> parsed = JSON.parseArray(configs.getConfigValue("membership.tiers", DEFAULT_TIERS), Tier.class);
            if (parsed != null && !parsed.isEmpty()) {
                parsed.sort(Comparator.comparing(Tier::minSpend));
                return parsed;
            }
        } catch (Exception ignored) {}
        List<Tier> fallback = JSON.parseArray(DEFAULT_TIERS, Tier.class);
        fallback.sort(Comparator.comparing(Tier::minSpend));
        return fallback;
    }

    public record Tier(String code, String name, BigDecimal minSpend, BigDecimal discountRate) {}
}
