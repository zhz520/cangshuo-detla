package com.delta.common.tenant;

/** Runs startup checks once in legacy mode and once per active tenant in shared mode. */
public final class TenantStartup {
    private TenantStartup() {
    }

    public static void forEachActive(TenantRegistry registry, TenantTask task) {
        if (registry == null || !registry.isSharedEnabled()) {
            invoke(null, task);
            return;
        }
        for (TenantDefinition tenant : registry.activeTenants()) {
            invoke(tenant.id(), task);
        }
    }

    private static void invoke(String tenantId, TenantTask task) {
        try {
            if (tenantId == null) {
                task.run();
            } else {
                try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                    task.run();
                }
            }
        } catch (Exception error) {
            String scope = tenantId == null ? "单租户" : "租户[" + tenantId + "]";
            throw new IllegalStateException(scope + "初始化失败", error);
        }
    }

    @FunctionalInterface
    public interface TenantTask {
        void run() throws Exception;
    }
}
