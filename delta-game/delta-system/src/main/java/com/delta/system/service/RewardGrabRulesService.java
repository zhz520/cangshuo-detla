package com.delta.system.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.exception.BusinessException;
import com.delta.system.entity.SysConfig;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RewardGrabRulesService {
    public static final String KEY = "features.reward_grab";
    private final SysConfigService configs;
    public record Rules(@NotNull Boolean rewardEnabled,
            @NotNull @DecimalMin("0.01") @DecimalMax("10000") @Digits(integer=5,fraction=2) BigDecimal minAmount,
            @NotNull @DecimalMin("0.01") @DecimalMax("10000") @Digits(integer=5,fraction=2) BigDecimal maxAmount,
            @NotNull @Min(1) @Max(365) Integer validDays,
            @NotNull @Min(1) @Max(1000) Integer userDailyLimit,
            @NotNull @Min(1) @Max(1000) Integer playerDailyLimit,
            @NotNull @Min(1) @Max(1000) Integer pairDailyLimit,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal feePercent,
            @NotNull @Min(30) @Max(600) Integer captchaSeconds,
            @NotNull @Min(1) @Max(10) Integer captchaAttempts,
            @NotNull @Min(3) @Max(120) Integer captchaRefreshSeconds) {}
    public Rules get() {
        // Read the committed database value; avoid stale cache repopulation during admin transactions.
        SysConfig row=configs.getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey,KEY));
        String value = row==null ? "" : row.getConfigValue();
        return value.isBlank() ? defaults() : JSON.parseObject(value, Rules.class);
    }
    public static Rules defaults() {
        return new Rules(false,new BigDecimal("1"),new BigDecimal("200"),30,10,5,5,
                BigDecimal.ZERO,120,5,10);
    }
    @Transactional(rollbackFor=Exception.class)
    public void save(Rules rules) {
        if(rules.minAmount().compareTo(rules.maxAmount())>0) throw new BusinessException(400,"最低金额不能高于最高金额");
        SysConfig row=configs.getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey,KEY));
        if(row==null) {
            row=new SysConfig();
            row.setConfigKey(KEY);
            row.setConfigName("打赏与抢单验证规则");
            row.setValueType("json");
            row.setConfigGroup("reward-grab");
            row.setRemark("在打赏与抢单设置页面修改");
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(row.getCreatedAt());
        }
        row.setConfigValue(JSON.toJSONString(rules));
        if(row.getId()==null) configs.save(row);
        else configs.updateById(row);
    }
}
