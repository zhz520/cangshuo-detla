package com.delta.admin.tenant;

import com.delta.common.enums.UserTypeEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.TokenService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTokenIsolationTest {
    @Mock RedisService redis;
    @Mock JdbcTemplate jdbc;
    private TokenService tokens;

    @BeforeEach
    void setUp() {
        tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "secret", "test-secret-that-is-long-enough-for-hs256-signing-123456");
        ReflectionTestUtils.setField(tokens, "expiration", 60_000L);
        ReflectionTestUtils.setField(tokens, "redisService", redis);
        ReflectionTestUtils.setField(tokens, "jdbc", jdbc);
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        ReflectionTestUtils.setField(tokens, "tenantProperties", properties);
    }

    @Test
    void tokenCarriesTenantAndCannotBeAuthenticatedUnderAnotherTenant() throws Exception {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(user()));
        String tokenA = TenantContext.callAs("tenant-a", () -> tokens.createToken(7L, UserTypeEnum.USER));
        String tokenB = TenantContext.callAs("tenant-b", () -> tokens.createToken(7L, UserTypeEnum.USER));

        assertThat(tokens.parseToken(tokenA).get("tenantId", String.class)).isEqualTo("tenant-a");
        assertThat(tokens.parseToken(tokenB).get("tenantId", String.class)).isEqualTo("tenant-b");
        assertThat(TenantContext.callAs("tenant-b", () -> tokens.authenticate(tokenA))).isNull();
        assertThat(TenantContext.callAs("tenant-a", () -> tokens.authenticate(tokenB))).isNull();
    }

    @Test
    void sharedModeRefusesToIssueAnUnboundToken() {
        assertThatThrownBy(() -> tokens.createToken(7L, UserTypeEnum.USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户上下文");
    }

    private static Map<String, Object> user() {
        Map<String, Object> row = new HashMap<>();
        row.put("status", 1);
        row.put("role", "USER");
        row.put("password", "hash");
        row.put("phone", "13800000000");
        row.put("openid", null);
        row.put("is_guest", 0);
        row.put("auth_version", 1);
        return row;
    }
}
