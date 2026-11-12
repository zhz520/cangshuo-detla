package com.delta.admin.controller;

import com.delta.common.domain.R;
import com.delta.common.membership.MembershipTierRules;
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
        List<MembershipTierRules.Tier> tiers = MembershipTierRules.parseStrict(
                configs.getConfigValue("membership.tiers", MembershipTierRules.DEFAULT_TIERS_JSON));
        MembershipTierRules.Tier current = tiers.get(0), next = null;
        for (MembershipTierRules.Tier tier : tiers) {
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

}
