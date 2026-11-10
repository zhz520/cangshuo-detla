package com.delta.common.tenant;

import lombok.extern.slf4j.Slf4j;

/** Executes a scheduled unit once in legacy mode or once per active tenant in shared mode. */
@Slf4j
public final class TenantScheduler {
    private TenantScheduler() {
    }

    public static void run(TenantRegistry registry, TenantTask task) {
        if (registry == null || !registry.isSharedEnabled()) {
            try {
                task.run();
            } catch (Exception error) {
                log.error("定时任务执行失败", error);
            }
            return;
        }
        for (TenantDefinition tenant : registry.activeTenants()) {
            try (TenantContext.Scope ignored = TenantContext.open(tenant.id())) {
                task.run();
            } catch (Exception error) {
                log.error("租户[{}]定时任务执行失败", tenant.id(), error);
            }
        }
    }

    @FunctionalInterface
    public interface TenantTask {
        void run() throws Exception;
    }
}
