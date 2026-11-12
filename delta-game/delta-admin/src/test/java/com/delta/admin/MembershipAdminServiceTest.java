package com.delta.admin;

import com.alibaba.fastjson2.JSON;
import com.delta.admin.service.MembershipAdminService;
import com.delta.common.exception.BusinessException;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MembershipAdminServiceTest {
    private static final String VALID_TIERS = "[{\"code\":\"NORMAL\",\"name\":\"普通会员\",\"minSpend\":0,\"discountRate\":1},{\"code\":\"SILVER\",\"name\":\"白银会员\",\"minSpend\":500,\"discountRate\":0.98}]";

    @Test
    void currentExposesTheEffectiveEnabledStateAndRules() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getConfigValue(eq("membership.tiers"), anyString())).thenReturn(VALID_TIERS);
        when(configs.getConfigValue("membership.enabled", "false")).thenReturn("true");

        Map<String, Object> result = new MembershipAdminService(configs).current();

        assertThat(result.get("enabled")).isEqualTo(true);
        assertThat((List<?>) result.get("tiers")).hasSize(2);
        assertThat(result.toString()).contains("SILVER");
    }

    @Test
    void saveRejectsInvalidRulesBeforeWritingAnything() {
        SysConfigService configs = mock(SysConfigService.class);
        MembershipAdminService service = new MembershipAdminService(configs);

        assertThatThrownBy(() -> service.save(true, List.of(Map.of(
                "code", "SILVER", "name", "白银会员", "minSpend", 500, "discountRate", 0.98))))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        verify(configs, never()).save(any(SysConfig.class));
        verify(configs, never()).updateById(any(SysConfig.class));
        verify(configs, never()).evictCache(anyString());
    }

    @Test
    void saveNormalizesAndPersistsBothConfigurationKeys() {
        SysConfigService configs = mock(SysConfigService.class);
        when(configs.getOne(any())).thenReturn(null);
        when(configs.getConfigValue(eq("membership.tiers"), anyString())).thenReturn(VALID_TIERS);
        when(configs.getConfigValue("membership.enabled", "false")).thenReturn("true");
        MembershipAdminService service = new MembershipAdminService(configs);

        service.save(true, List.of(
                Map.of("code", "silver", "name", "白银会员", "minSpend", 500, "discountRate", 0.98),
                Map.of("code", "normal", "name", "普通会员", "minSpend", 0, "discountRate", 1)));

        ArgumentCaptor<SysConfig> captor = ArgumentCaptor.forClass(SysConfig.class);
        verify(configs, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(SysConfig::getConfigKey)
                .containsExactly("membership.enabled", "membership.tiers");
        assertThat(JSON.parseArray(captor.getAllValues().get(1).getConfigValue()).getJSONObject(0).getString("code"))
                .isEqualTo("NORMAL");
        verify(configs).evictCache("membership.enabled");
        verify(configs).evictCache("membership.tiers");
    }
}
