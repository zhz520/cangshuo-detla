package com.delta.admin.platform;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/platform")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
@RequiredArgsConstructor
public class PlatformController {
    private final PlatformService platform;
    private final PlatformRemote remote;
    private final IncomeAnalysisService analysis;
    public record EnableRequest(@NotNull Boolean enabled) {}
    public record UpdateSelectionRequest(List<String> components) {}
    public record CheckoutRequest(String moduleId) {}
    public record MarketView(PlatformService.Overview overview, List<PlatformService.FeatureView> features,
                             boolean authorityConfigured, String lastSync) {}

    @GetMapping("/market")
    public R<MarketView> market() { return R.ok(marketView()); }

    @PostMapping("/market/sync")
    public R<MarketView> syncMarket() {
        remote.syncFeatureMarket();
        return R.ok(marketView());
    }

    private MarketView marketView() {
        return new MarketView(platform.overview(), withCommercialMetadata(platform.features()),
                remote.authorityConfigured(), remote.lastSuccessfulSync());
    }

    @GetMapping("/overview") public R<PlatformService.Overview> overview() { return R.ok(platform.overview()); }
    @GetMapping("/features")
    public R<List<PlatformService.FeatureView>> features() {
        return R.ok(withCommercialMetadata(platform.features()));
    }
    List<PlatformService.FeatureView> withCommercialMetadata(List<PlatformService.FeatureView> source) {
        var commercial = remote.commercialModules();
        return source.stream().map(feature -> {
            var module = commercial.get(feature.id());
            if (module == null && !feature.managedByAuthority()) return feature;
            Integer price;
            if (module != null && module.path("priceCents").isNumber()) {
                price = module.path("priceCents").asInt();
            } else if (feature.managedByAuthority()) {
                price = null;
            } else {
                price = feature.priceCents();
            }
            Integer termDays = module != null && module.path("termDays").isIntegralNumber()
                    && module.path("termDays").asInt() >= 0 && module.path("termDays").asInt() <= 3660
                    ? module.path("termDays").asInt() : feature.termDays();
            String minimum = module == null ? feature.minVersion() : module.path("minVersion").asText(feature.minVersion());
            return new PlatformService.FeatureView(feature.id(), feature.name(), feature.description(), feature.paid(),
                    price, termDays, minimum, feature.dependencies(), feature.entryPath(), feature.capabilities(), feature.status(),
                    feature.entitled(), feature.enabled(), feature.reason(), feature.startsAt(), feature.expiresAt(),
                    feature.managedByAuthority());
        }).toList();
    }
    @GetMapping("/updates") public R<PlatformService.UpdateInfo> updates() { return R.ok(platform.updates()); }
    @PutMapping("/updates/selection")
    @OpLog(module="platform", operation="选择客户端更新内容")
    public R<PlatformService.UpdateInfo> updateSelection(@RequestBody UpdateSelectionRequest body) {
        return R.ok(platform.setUpdateComponents(body.components()));
    }

    @GetMapping("/updates/remote") public R<com.fasterxml.jackson.databind.JsonNode> remoteStatus() { return R.ok(remote.status()); }
    @PostMapping("/checkout")
    @OpLog(module="platform", operation="发起功能购买")
    public R<Map<String,String>> checkout(@RequestBody CheckoutRequest body) {
        return R.ok(Map.of("checkoutUrl",remote.checkout(body.moduleId())));
    }
    @PostMapping("/updates/check") public R<com.fasterxml.jackson.databind.JsonNode> checkUpdates() { return R.ok(remote.refresh()); }
    @PostMapping("/updates/install")
    @OpLog(module="platform", operation="安装客户端更新")
    public R<com.fasterxml.jackson.databind.JsonNode> install(@RequestBody com.fasterxml.jackson.databind.JsonNode body) { return R.ok(remote.install(body.path("selection"))); }

    // License payloads are deliberately excluded from the generic request-body operation logger.
    @PostMapping("/license/import")
    public R<PlatformService.Overview> importLicense(@Valid @RequestBody PlatformLicense.Envelope body) {
        return R.ok(platform.importLicense(body));
    }
    @PutMapping("/features/{id}/enabled")
    @OpLog(module="platform", operation="调整扩展功能启用状态")
    public R<List<PlatformService.FeatureView>> enabled(@PathVariable String id, @Valid @RequestBody EnableRequest body) {
        return R.ok(withCommercialMetadata(platform.setEnabled(id, body.enabled())));
    }
    @GetMapping("/reports/income")
    public R<IncomeAnalysisService.Comparison> income(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        return R.ok(analysis.compare(startDate, endDate, request.getServerName()));
    }
}
