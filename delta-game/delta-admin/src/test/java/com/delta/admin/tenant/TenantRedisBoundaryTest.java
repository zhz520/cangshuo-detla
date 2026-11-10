package com.delta.admin.tenant;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantKeyEncoder;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRedisBoundaryTest {
    @Test
    void identicalBusinessKeysNeverCollideAcrossTenants() throws Exception {
        TenantKeyEncoder keys = new TenantKeyEncoder(new TenantRegistry(properties(true)));

        String a = TenantContext.callAs("tenant-a", () -> keys.key("login_tokens:abc"));
        String b = TenantContext.callAs("tenant-b", () -> keys.key("login_tokens:abc"));

        assertThat(a).isEqualTo("delta:tenant-a:login_tokens:abc");
        assertThat(b).isEqualTo("delta:tenant-b:login_tokens:abc");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sharedModeFailsClosedWithoutTenantContext() {
        TenantKeyEncoder keys = new TenantKeyEncoder(new TenantRegistry(properties(true)));

        assertThatThrownBy(() -> keys.key("login_tokens:abc"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户上下文");
    }

    @Test
    void legacyModeRetainsExistingRedisKeys() {
        TenantKeyEncoder keys = new TenantKeyEncoder(new TenantRegistry(properties(false)));

        assertThat(keys.key("login_tokens:abc")).isEqualTo("login_tokens:abc");
    }

    @Test
    void aThreadCannotSilentlySwitchTenantInsideAnOpenScope() {
        try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
            assertThatThrownBy(() -> TenantContext.open("tenant-b"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("禁止切换租户");
        } finally {
            TenantContext.clear();
        }
    }

    private TenantProperties properties(boolean enabled) {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(enabled);
        TenantProperties.Tenant a = new TenantProperties.Tenant();
        a.setHosts(List.of("shop-a.example.com"));
        TenantProperties.Tenant b = new TenantProperties.Tenant();
        b.setHosts(List.of("shop-b.example.com"));
        properties.getTenants().put("tenant-a", a);
        properties.getTenants().put("tenant-b", b);
        return properties;
    }
}
