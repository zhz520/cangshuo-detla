package com.delta.common.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Adds the tenant boundary to every Redis key handled by the shared service. */
@Component
@RequiredArgsConstructor
public class TenantKeyEncoder {
    private final TenantRegistry registry;

    public String key(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) throw new IllegalArgumentException("Redis键不能为空");
        if (!registry.isSharedEnabled()) return rawKey;
        return "delta:" + TenantContext.requireId() + ":" + rawKey;
    }
}
