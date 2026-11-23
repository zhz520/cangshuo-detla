package com.delta.admin.tenant;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test
    void namedJobSkipsOverlappingRunsAndStillCleansTenantContext() throws Exception {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        TenantProperties.Tenant a = new TenantProperties.Tenant();
        a.setHosts(List.of("a.example.com"));
        properties.getTenants().put("tenant-a", a);
        TenantRegistry registry = new TenantRegistry(properties);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> TenantScheduler.run("test-job", registry, () -> {
                calls.incrementAndGet();
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            TenantScheduler.run("test-job", registry, calls::incrementAndGet);
            assertThat(calls).hasValue(1);
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertThat(TenantContext.currentIdOrNull()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
