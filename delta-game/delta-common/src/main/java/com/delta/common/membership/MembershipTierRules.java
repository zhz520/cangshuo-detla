package com.delta.common.membership;

import com.alibaba.fastjson2.JSON;
import com.delta.common.exception.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict boundary for the tenant-editable membership JSON. */
public final class MembershipTierRules {
    private static final BigDecimal MAX_THRESHOLD = new BigDecimal("100000000.00");
    private MembershipTierRules() {}

    public record Tier(String code, String name, BigDecimal minSpend, BigDecimal discountRate) {}

    public static List<Tier> parseStrict(String raw) {
        List<Tier> tiers;
        try {
            tiers = JSON.parseArray(raw, Tier.class);
        } catch (Exception error) {
            throw invalid("会员等级规则不是有效 JSON");
        }
        if (tiers == null || tiers.isEmpty() || tiers.size() > 20)
            throw invalid("会员等级数量须为 1 至 20 个");
        List<Tier> normalized = new ArrayList<>(tiers.size());
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Tier source : tiers) {
            if (source == null) throw invalid("会员等级不能为空");
            String code = source.code() == null ? "" : source.code().trim().toUpperCase(Locale.ROOT);
            String name = source.name() == null ? "" : source.name().trim();
            if (!code.matches("[A-Z][A-Z0-9_]{0,31}")) throw invalid("会员等级编码须为 1 至 32 位大写字母、数字或下划线");
            if (name.isEmpty() || name.length() > 30) throw invalid("会员等级名称须为 1 至 30 个字符");
            if (!codes.add(code) || !names.add(name)) throw invalid("会员等级编码和名称不能重复");
            BigDecimal threshold = source.minSpend() == null ? BigDecimal.ZERO : source.minSpend();
            BigDecimal rate = source.discountRate();
            if (threshold.signum() < 0 || threshold.compareTo(MAX_THRESHOLD) > 0 || threshold.scale() > 2)
                throw invalid("会员累计消费门槛须为 0 至 100000000 元，最多两位小数");
            if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0)
                throw invalid("会员折扣率须大于 0 且不超过 1");
            normalized.add(new Tier(code, name, threshold.setScale(2), rate));
        }
        normalized.sort(Comparator.comparing(Tier::minSpend));
        if (normalized.get(0).minSpend().signum() != 0) throw invalid("最低会员等级门槛必须为 0 元");
        for (int i = 1; i < normalized.size(); i++) {
            if (normalized.get(i).minSpend().compareTo(normalized.get(i - 1).minSpend()) <= 0)
                throw invalid("会员累计消费门槛必须逐级递增");
        }
        return List.copyOf(normalized);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }
}
