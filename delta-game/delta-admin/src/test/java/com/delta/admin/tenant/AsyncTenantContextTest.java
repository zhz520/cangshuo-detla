package com.delta.admin.tenant;

import com.delta.common.config.AsyncConfig;
import com.delta.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTenantContextTest {
    @AfterEach
    void clearCurrentThread() {
        TenantContext.clear();
    }

    @Test
    void asyncMethodUsesDecoratedExecutorWhenSchedulerIsAlsoPresent() throws Exception {
        AtomicReference<String> seenTenant = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, ProbeConfiguration.class);
            context.refresh();

            try (TenantContext.Scope ignored = TenantContext.open("tenant-a")) {
                context.getBean(AsyncProbe.class).capture(seenTenant, completed);
            }

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seenTenant).hasValue("tenant-a");
        }
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }

    @Configuration
    static class ProbeConfiguration {
        @Bean
        TaskScheduler scheduledTasks() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("tenant-test-scheduler-");
            scheduler.initialize();
            return scheduler;
        }

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    static class AsyncProbe {
        @Async
        public void capture(AtomicReference<String> seenTenant, CountDownLatch completed) {
            try {
                seenTenant.set(TenantContext.currentIdOrNull());
            } finally {
                completed.countDown();
            }
        }
    }
}
