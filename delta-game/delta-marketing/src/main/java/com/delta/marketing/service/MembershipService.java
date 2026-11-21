package com.delta.marketing.service;

import com.delta.common.membership.MembershipTierRules;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer membership tier calculation. */
@Service
@RequiredArgsConstructor
public class MembershipService {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    public Map<String, Object> current(Long userId) {
        if (!"true".equalsIgnoreCase(configs.getConfigValue("membership.enabled", "false")))
            return Map.of("enabled", false, "tier", Map.of(), "netSpend", BigDecimal.ZERO);
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
        return result;
    }
}
