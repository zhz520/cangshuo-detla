package com.delta.admin.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformReporter {
    private final PlatformRemote remote;
    @Scheduled(initialDelayString = "${platform.report.initial-delay-ms:15000}",
            fixedDelayString = "${platform.report.interval-ms:300000}")
    public void report() {
        try { remote.refresh(); }
        catch (Exception error) { log.warn("客户端授权与版本同步暂不可用：{}", error.getClass().getSimpleName()); }
    }
}
