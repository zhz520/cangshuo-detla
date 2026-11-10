package com.delta.common.tenant;

import com.delta.common.exception.BusinessException;

/** The request or background task's tenant boundary. Never populated from client input. */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String currentIdOrNull() {
        return CURRENT.get();
    }

    public static String requireId() {
        String id = CURRENT.get();
        if (id == null) throw new BusinessException(503, "租户上下文不可用，请联系维护人员");
        return id;
    }

    public static Scope open(String tenantId) {
        String valid = TenantIds.requireContext(tenantId);
        String previous = CURRENT.get();
        if (previous != null && !previous.equals(valid)) {
            throw new BusinessException(503, "同一线程内禁止切换租户");
        }
        if (previous == null) CURRENT.set(valid);
        return new Scope(previous);
    }

    public static void runAs(String tenantId, Runnable action) {
        try (Scope ignored = open(tenantId)) {
            action.run();
        }
    }

    public static <T> T callAs(String tenantId, TenantCallable<T> action) throws Exception {
        try (Scope ignored = open(tenantId)) {
            return action.call();
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    @FunctionalInterface
    public interface TenantCallable<T> {
        T call() throws Exception;
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
