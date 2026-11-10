package com.delta.admin.tenant;

import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantTaskDecoratorTest {
    @AfterEach
    void clearCurrentThread() {
        TenantContext.clear();
    }

    @Test
    void asynchronousTaskReceivesTenantAndWorkerContextIsCleared() throws Exception {
        TenantTaskDecorator decorator = new TenantTaskDecorator();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        var pool = Executors.newSingleThreadExecutor();
        try {
            Runnable tenantA = TenantContext.callAs("tenant-a", () -> decorator.decorate(() -> first.set(TenantContext.currentIdOrNull())));
            pool.submit(tenantA).get(5, TimeUnit.SECONDS);
            pool.submit(() -> {
                after.set(TenantContext.currentIdOrNull());
            }).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(first).hasValue("tenant-a");
        assertThat(after.get()).isNull();
    }
}
