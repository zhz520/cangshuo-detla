package com.delta.admin.tenant;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchedulerTest {
    @Test
    void runsOncePerActiveTenantAndDoesNotLeakContext() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        TenantProperties.Tenant a = new TenantProperties.Tenant();
        a.setHosts(List.of("a.example.com"));
        TenantProperties.Tenant b = new TenantProperties.Tenant();
        b.setHosts(List.of("b.example.com"));
        TenantProperties.Tenant inactive = new TenantProperties.Tenant();
        inactive.setHosts(List.of("old.example.com"));
        inactive.setActive(false);
        properties.getTenants().put("tenant-a", a);
        properties.getTenants().put("tenant-b", b);
        properties.getTenants().put("tenant-old", inactive);
        AtomicInteger calls = new AtomicInteger();

        TenantScheduler.run(new TenantRegistry(properties), () -> {
            if (TenantContext.currentIdOrNull() == null) throw new IllegalStateException("missing tenant");
            calls.incrementAndGet();
        });

        assertThat(calls).hasValue(2);
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }
}
