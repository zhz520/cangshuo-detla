package com.delta.common.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Executes a scheduled unit once in legacy mode or once per active tenant in shared mode. */
@Slf4j
public final class TenantScheduler {
    private static final ConcurrentHashMap<String, ReentrantLock> JOB_LOCKS = new ConcurrentHashMap<>();

    private TenantScheduler() {
    }

    /**
     * Runs one named job at most once in this process. Per-tenant execution and
     * context cleanup remain identical to {@link #run(TenantRegistry, TenantTask)}.
     */
    public static void run(String jobId, TenantRegistry registry, TenantTask task) {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId is required");
        ReentrantLock lock = JOB_LOCKS.computeIfAbsent(jobId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("定时任务[{}]仍在执行，跳过本轮重叠调度", jobId);
            return;
        }
        long started = System.nanoTime();
        try {
            run(registry, task);
            log.info("定时任务[{}]完成，耗时{}ms", jobId, (System.nanoTime() - started) / 1_000_000);
        } finally {
            lock.unlock();
        }
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
