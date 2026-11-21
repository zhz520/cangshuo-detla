package com.delta.marketing.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.exception.BusinessException;
import com.delta.common.membership.MembershipTierRules;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MembershipAdminService {
    private static final String ENABLED_KEY = "membership.enabled";
    private static final String TIERS_KEY = "membership.tiers";

    private final SysConfigService configs;

    public Map<String, Object> current() {
        List<MembershipTierRules.Tier> tiers = MembershipTierRules.parseStrict(
                configs.getConfigValue(TIERS_KEY, MembershipTierRules.DEFAULT_TIERS_JSON));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", "true".equalsIgnoreCase(configs.getConfigValue(ENABLED_KEY, "false")));
        result.put("tiers", tiers);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(boolean enabled, List<?> tiersValue) {
        List<MembershipTierRules.Tier> tiers = MembershipTierRules.parseStrict(JSON.toJSONString(tiersValue));
        save(ENABLED_KEY, enabled ? "true" : "false", "启用用户会员等级", "boolean", "关闭后停止新增会员权益，既有等级和历史记录保留");
        save(TIERS_KEY, JSON.toJSONString(tiers), "会员等级规则", "json", "按有效净消费成长；minSpend 单位元，discountRate 为 0 到 1 之间的折扣率");
        return current();
    }

    private void save(String key, String value, String name, String type, String remark) {
        SysConfig current = configs.getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (current == null) {
            SysConfig created = new SysConfig();
            created.setConfigKey(key);
            created.setConfigName(name);
            created.setConfigValue(value);
            created.setValueType(type);
            created.setConfigGroup("会员配置");
            created.setRemark(remark);
            configs.save(created);
        } else {
            current.setConfigValue(value);
            configs.updateById(current);
        }
        configs.evictCache(key);
    }
}
