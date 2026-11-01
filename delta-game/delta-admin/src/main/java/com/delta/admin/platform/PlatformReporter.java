package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformReporter {
    private final PlatformProperties properties;
    private final PlatformService platform;
    private final ObjectMapper json;

    @Scheduled(initialDelayString = "${platform.report.initial-delay-ms:15000}",
            fixedDelayString = "${platform.report.interval-ms:21600000}")
    public void report() {
        String target = properties.getReportUrl();
        if (target == null || target.isBlank()) return;
        var payload = platform.reportPayload();
        if (payload == null) return;
        try {
            var request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) log.warn("客户端版本上报失败，状态码 {}", response.statusCode());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            log.warn("客户端版本上报暂不可用：{}", error.getClass().getSimpleName());
        }
    }
}
