package com.delta.admin.pay;

import com.delta.pay.service.impl.PaymentServiceImpl;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerDepositPolicyTest {
    private PaymentServiceImpl service(String mode,List<Map<String,Object>> levels) {
        var configs=mock(SysConfigService.class);var jdbc=mock(JdbcTemplate.class);
        when(configs.getConfigValue(eq("player.deposit_mode"),anyString())).thenReturn(mode);
        when(configs.getConfigValue(eq("player.deposit_amount"),eq("100"))).thenReturn("100");
        when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(levels);
        return new PaymentServiceImpl(null,null,null,null,null,configs,null,null,null,jdbc);
    }

    @Test void originalRequiredModeKeepsTheGlobalDepositAmount() {
        assertThat(service("REQUIRED",List.of()).playerDepositRequiredAmount(7L)).isEqualByComparingTo("100.00");
    }

    @Test void noDepositModeAlwaysReturnsZero() {
        assertThat(service("NONE",List.of()).playerDepositRequiredAmount(7L)).isEqualByComparingTo("0");
    }

    @Test void beforeWithdrawModeUsesTheAssignedPlayerLevelAmount() {
        assertThat(service("BEFORE_WITHDRAW",List.of(Map.of("amount",new BigDecimal("88.50")))).playerDepositRequiredAmount(7L)).isEqualByComparingTo("88.50");
    }

    @Test void perPlayerOverrideWinsOverGlobalAndLevelPolicy() {
        var configs=mock(SysConfigService.class);var jdbc=mock(JdbcTemplate.class);
        when(configs.getConfigValue(eq("player.deposit_mode"),anyString())).thenReturn("NONE");
        when(jdbc.query(anyString(),any(ResultSetExtractor.class),eq(7L))).thenReturn(new BigDecimal("66.00"));
        var service=new PaymentServiceImpl(null,null,null,null,null,configs,null,null,null,jdbc);
        assertThat(service.playerDepositRequiredAmount(7L)).isEqualByComparingTo("66.00");
    }
}
