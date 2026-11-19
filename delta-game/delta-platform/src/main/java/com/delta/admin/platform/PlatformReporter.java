package com.delta.admin.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformReporter {
    private final PlatformRemote remote;
    @Autowired(required=false) private TenantRegistry tenantRegistry;
    @Scheduled(initialDelayString = "${platform.report.initial-delay-ms:15000}",
            fixedDelayString = "${platform.report.interval-ms:300000}")
    public void report() {
        TenantScheduler.run(tenantRegistry,this::reportForTenant);
    }
    private void reportForTenant() {
        try { remote.refresh(); }
        catch (Exception error) { log.warn("客户端授权与版本同步暂不可用：{}", error.getClass().getSimpleName()); }
    }
}
