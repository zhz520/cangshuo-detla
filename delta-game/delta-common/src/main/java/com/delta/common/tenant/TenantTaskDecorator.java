package com.delta.common.tenant;

import org.springframework.core.task.TaskDecorator;

/** Carries the trusted tenant boundary into an asynchronous task, then clears it. */
public class TenantTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        String tenantId = TenantContext.currentIdOrNull();
        return () -> {
            if (tenantId == null) {
                TenantContext.clear();
                try { task.run(); } finally { TenantContext.clear(); }
                return;
            }
            try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                task.run();
            }
        };
    }
}
