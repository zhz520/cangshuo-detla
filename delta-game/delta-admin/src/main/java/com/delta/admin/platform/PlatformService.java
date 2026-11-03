package com.delta.admin.platform;

import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class PlatformService {
    private final PlatformProperties properties;
    private final PlatformCatalog catalog;
    private final PlatformStateStore store;
    private final LicenseVerifier verifier;
    private final Clock clock;

    @Autowired
    public PlatformService(PlatformProperties properties, ObjectMapper json) {
        this(properties, json, Clock.systemUTC());
    }
    PlatformService(PlatformProperties properties, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.catalog = PlatformCatalog.load(json);
        this.store = new PlatformStateStore(properties.getStatePath(), json);
        this.verifier = new LicenseVerifier(properties, json, catalog);
        this.clock = clock;
    }

    public record FeatureView(String id, String name, String description, boolean paid, Integer priceCents, Integer termDays,
                              String minVersion,
                              List<String> dependencies, String entryPath, List<String> capabilities,
                              String status, boolean entitled, boolean enabled, String reason, Instant startsAt,
                              Instant expiresAt, boolean managedByAuthority) {}
    public record Overview(String customerId, String instanceId, String version, String channel,
                           boolean verifierConfigured, String licenseStatus, String licenseId, Instant expiresAt,
                           String purchaseMode, String updateMode, List<String> allowedDomains, boolean managedByAuthority) {}
    public record UpdateInfo(String version, String channel, int schemaVersion, List<String> releaseNotes,
                             boolean automaticUpdateAvailable, String status, String message,
                             String sourceUrl, List<UpdateOption> components) {}
    public record UpdateOption(String id, String name, String description, boolean selected) {}
    public record ReportPayload(String customerId, String instanceId, String version,
                                PlatformLicense.Envelope license) {}

    public synchronized Overview overview() {
        var state = store.load();
        var claims = claims(state);
        return new Overview(properties.getCustomerId(), state.instanceId(), catalog.version(), catalog.channel(),
                verifier.configured(), licenseStatus(state, claims), claims == null ? null : claims.licenseId(),
                claims == null ? null : claims.expiresAt(), "MANUAL", "MANUAL",
                claims == null || claims.allowedDomains() == null ? List.of() : claims.allowedDomains(),
                !state.remoteFeatureEnabled().isEmpty() || state.remoteProject() != null);
    }

    public synchronized List<FeatureView> features() {
        var state = store.load();
        var claims = claims(state);
        return catalog.features().stream().map(feature -> view(feature, state, claims)).toList();
    }

    public UpdateInfo updates() {
        var selected = store.load().updateComponents();
        var components = List.of(
                option("core_platform", "基础业务与安全", "后端、数据库迁移和基础安全修复", selected),
                option("admin_console", "管理后台", "管理员页面与运营配置界面", selected),
                option("mobile_client", "移动端/H5", "用户端、接单员端和客服端页面", selected),
                option("image_rendering", "多比例图片显示", "商品、分类、轮播和聊天图片完整显示", selected),
                option("advanced_income_reports", "收益对比分析", "付费扩展模块与每日汇总导出", selected));
        return new UpdateInfo(catalog.version(), catalog.channel(), catalog.schemaVersion(), catalog.releaseNotes(),
                false, "VERSION_REPORTED", "客户端版本由实例自动上报；更新安装仍由维护人员执行。",
                "https://github.com/zhz520/cangshuo-detla/releases/tag/v" + catalog.version(), components);
    }
    public synchronized UpdateInfo setUpdateComponents(List<String> requested) {
        if (requested == null || requested.size() > 20) throw new BusinessException(400, "更新内容选择不正确");
        var allowed = Set.of("core_platform","admin_console","mobile_client","image_rendering","advanced_income_reports");
        if (!allowed.containsAll(requested) || new HashSet<>(requested).size() != requested.size())
            throw new BusinessException(400, "更新内容包含未知项目");
        var state = store.load();
        store.save(new PlatformStateStore.State(1, state.instanceId(), state.license(), state.enabledFeatures(),
                state.remoteFeatureEnabled(), state.remoteFeatureStartsAt(), state.remoteFeatureExpiresAt(),
                Set.copyOf(requested), state.remoteProject()));
        return updates();
    }
    public synchronized ReportPayload reportPayload() {
        var state = store.load();
        var claims = claims(state);
        if (claims == null) return null;
        return new ReportPayload(properties.getCustomerId(), state.instanceId(), catalog.version(), state.license());
    }

    /** Applies one verified authority response to the license and switches in a single atomic state write. */
    synchronized void applyVerifiedAuthoritySnapshot(PlatformLicense.Envelope envelope,
                                                     JsonNode project,
                                                     Map<String, Boolean> advertisedEnabled,
                                                     Map<String, Instant> advertisedStartsAt,
                                                     Map<String, Instant> advertisedExpiresAt) {
        if ((advertisedEnabled == null) != (advertisedStartsAt == null)
                || (advertisedStartsAt == null) != (advertisedExpiresAt == null))
            throw new IllegalArgumentException("remote feature controls must be supplied together");
        if (advertisedStartsAt != null && !advertisedStartsAt.keySet().equals(advertisedExpiresAt.keySet()))
            throw new IllegalArgumentException("remote feature period keys do not match");
        if (advertisedEnabled != null && !advertisedEnabled.keySet().containsAll(advertisedStartsAt.keySet()))
            throw new IllegalArgumentException("remote feature periods lack enablement state");
        if (advertisedStartsAt != null) for (var entry : advertisedStartsAt.entrySet()) {
            Instant expiry = advertisedExpiresAt.get(entry.getKey());
            if (entry.getValue() == null || expiry == null || !entry.getValue().isBefore(expiry))
                throw new IllegalArgumentException("remote feature period is invalid");
        }
        var state = store.load();
        if (project != null) {
            Instant incoming = projectIssuedAt(project), previous = state.remoteProject() == null ? null : projectIssuedAt(state.remoteProject());
            if (previous != null && !incoming.isAfter(previous))
                throw new IllegalArgumentException("project snapshot is not newer");
        }
        if (advertisedEnabled != null) {
            if (project == null) throw new IllegalArgumentException("verified project snapshot is required");
        } else if (!state.remoteFeatureEnabled().isEmpty()) {
            throw new IllegalArgumentException("remote feature controls cannot be removed by an older protocol response");
        }
        var license = state.license();
        var enabled = new HashSet<>(state.enabledFeatures());
        PlatformLicense.Claims next = null;
        String projectLicenseId = project == null ? null : projectLicenseId(project);
        if (envelope != null) {
            next = verifyReplacement(state, envelope);
            if (advertisedEnabled != null && !next.licenseId().equals(projectLicenseId))
                throw new IllegalArgumentException("project and license snapshots do not match");
            license = envelope;
            enabled.retainAll(next.features());
        } else if (projectLicenseId != null) {
            throw new IllegalArgumentException("project snapshot references a missing license");
        }
        if (advertisedEnabled != null) {
            var current = next != null ? next : claims(state);
            for (var entry : advertisedEnabled.entrySet()) if (entry.getValue() && (current == null
                    || !current.features().contains(entry.getKey())))
                throw new IllegalArgumentException("remote feature is absent from the license");
        }
        Map<String, Boolean> managed;
        if (advertisedEnabled == null) {
            managed = state.remoteFeatureEnabled();
        } else {
            var replaced = new LinkedHashMap<>(advertisedEnabled);
            for (var feature : catalog.features()) if (feature.paid()) replaced.putIfAbsent(feature.id(), false);
            managed = Map.copyOf(replaced);
        }
        Map<String, Instant> startsAt = advertisedStartsAt == null ? state.remoteFeatureStartsAt() : Map.copyOf(advertisedStartsAt);
        Map<String, Instant> expiresAt = advertisedExpiresAt == null ? state.remoteFeatureExpiresAt() : Map.copyOf(advertisedExpiresAt);
        store.save(new PlatformStateStore.State(1, state.instanceId(), license, enabled, managed, startsAt, expiresAt,
                state.updateComponents(), project == null ? state.remoteProject() : project));
    }

    public synchronized Overview importLicense(PlatformLicense.Envelope envelope) {
        var state = store.load();
        if (!state.remoteFeatureEnabled().isEmpty() || state.remoteProject() != null)
            throw new BusinessException(409, "该实例已由授权中心托管，请等待自动同步或联系运营人员更新授权");
        var next = verifyReplacement(state, envelope);
        var enabled = new HashSet<>(state.enabledFeatures());
        enabled.retainAll(next.features());
        store.save(new PlatformStateStore.State(1, state.instanceId(), envelope, enabled,
                state.remoteFeatureEnabled(), state.remoteFeatureStartsAt(), state.remoteFeatureExpiresAt(),
                state.updateComponents(), state.remoteProject()));
        return overview();
    }

    private PlatformLicense.Claims verifyReplacement(PlatformStateStore.State state, PlatformLicense.Envelope envelope) {
        var next = verifier.verify(envelope, state.instanceId(), clock.instant());
        if (next.expiresAt() != null && !next.expiresAt().isAfter(clock.instant()))
            throw new BusinessException(400, "该授权已经到期，请联系维护人员续期");
        var previous = claims(state);
        if (previous != null && (next.issuedAt().isBefore(previous.issuedAt())
                || (next.issuedAt().equals(previous.issuedAt()) && !next.licenseId().equals(previous.licenseId()))))
            throw new BusinessException(409, "不能使用旧授权覆盖当前授权，请联系维护人员重新签发");
        return next;
    }

    public synchronized List<FeatureView> setEnabled(String id, boolean enabled) {
        var feature = catalog.features().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(404, "功能模块不存在"));
        if (!feature.paid()) throw new BusinessException(400, "基础业务功能始终可用，无需开关");
        var state = store.load();
        var claims = claims(state);
        var current = view(feature, state, claims);
        if (enabled && !current.entitled()) throw new BusinessException(403, current.reason());
        if (enabled && !feature.dependencies().stream().allMatch(dependency -> {
            var item = catalog.features().stream().filter(value -> value.id().equals(dependency)).findFirst().orElseThrow();
            return view(item, state, claims).enabled();
        })) throw new BusinessException(409, "请先启用此功能依赖的模块");
        if (!enabled && catalog.features().stream().anyMatch(item -> state.enabledFeatures().contains(item.id()) && item.dependencies().contains(id)))
            throw new BusinessException(409, "请先关闭依赖此模块的功能");
        var selected = new HashSet<>(state.enabledFeatures());
        if (enabled) selected.add(id); else selected.remove(id);
        store.save(new PlatformStateStore.State(1, state.instanceId(), state.license(), selected,
                state.remoteFeatureEnabled(), state.remoteFeatureStartsAt(), state.remoteFeatureExpiresAt(),
                state.updateComponents(), state.remoteProject()));
        return features();
    }

    synchronized JsonNode remoteProject() { return store.load().remoteProject(); }

    public synchronized void requireFeature(String id) {
        requireFeature(id, null);
    }
    public synchronized boolean featureEnabled(String id) {
        var state = store.load();
        return catalog.features().stream().filter(item -> item.id().equals(id)).findFirst()
                .map(feature -> view(feature, state, claims(state)).enabled())
                .orElse(false);
    }
    public synchronized boolean featureEnabled(String id, String requestDomain) {
        if (requestDomain == null || requestDomain.isBlank()) return featureEnabled(id);
        var state = store.load();
        var claims = claims(state);
        return catalog.features().stream().filter(item -> item.id().equals(id)).findFirst()
                .map(feature -> view(feature, state, claims).enabled() && domainAllowed(claims, requestDomain))
                .orElse(false);
    }
    public synchronized void requireFeature(String id, String requestDomain) {
        var state = store.load();
        var feature = catalog.features().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(404, "功能模块不存在"));
        var claims = claims(state);
        var view = view(feature, state, claims);
        if (!view.enabled()) throw new BusinessException(403, view.reason());
        if (!domainAllowed(claims, requestDomain))
            throw new BusinessException(403, "当前访问域名未获得此模块授权，请使用购买时登记的域名");
    }

    private PlatformLicense.Claims claims(PlatformStateStore.State state) {
        if (state.license() == null) return null;
        try { return verifier.verify(state.license(), state.instanceId(), clock.instant()); }
        catch (BusinessException error) { return null; }
    }

    private String licenseStatus(PlatformStateStore.State state, PlatformLicense.Claims claims) {
        if (state.license() == null) return "NONE";
        if (claims == null) return "INVALID";
        if (clock.instant().isBefore(claims.notBefore())) return "NOT_YET_VALID";
        if (claims.expiresAt() != null && !clock.instant().isBefore(claims.expiresAt())) return "EXPIRED";
        return "VALID";
    }

    private FeatureView view(PlatformCatalog.Feature feature, PlatformStateStore.State state, PlatformLicense.Claims claims) {
        String status, reason;
        boolean entitled, enabled;
        if (!feature.paid()) { status = "INCLUDED"; entitled = true; enabled = true; reason = "基础功能已包含"; }
        else {
            boolean managedByAuthority = state.remoteFeatureEnabled().containsKey(feature.id());
            String licenseStatus = licenseStatus(state, claims);
            boolean licensedFeature = claims != null && claims.features().contains(feature.id());
            boolean remoteFeature = state.remoteFeatureStartsAt().containsKey(feature.id())
                    || state.remoteFeatureExpiresAt().containsKey(feature.id());
            boolean purchased = licensedFeature || remoteFeature;
            Instant start = state.remoteFeatureStartsAt().get(feature.id());
            if (start == null && licensedFeature) start = claims.notBefore();
            Instant expiry = state.remoteFeatureExpiresAt().get(feature.id());
            if (expiry == null && licensedFeature) expiry = featureExpiry(claims, feature.id());
            boolean expired = purchased && ("EXPIRED".equals(licenseStatus) || (expiry != null && !clock.instant().isBefore(expiry)));
            boolean authorityAuthorized = !managedByAuthority || Boolean.TRUE.equals(state.remoteFeatureEnabled().get(feature.id()));
            entitled = "VALID".equals(licenseStatus) && purchased && !expired && authorityAuthorized;
            enabled = entitled && state.enabledFeatures().contains(feature.id());
            status = enabled ? "ENABLED" : entitled ? "DISABLED" : expired ? "EXPIRED"
                    : managedByAuthority && !authorityAuthorized && purchased ? "REVOKED" : "NOT_PURCHASED";
            reason = enabled ? "已启用" : entitled ? "已授权，尚未启用" : expired
                    ? "模块授权已到期，请联系维护人员续期"
                    : managedByAuthority && !authorityAuthorized && purchased ? "授权中心已暂停此功能授权"
                    : "此功能尚未获得有效授权，请联系维护人员开通";
            return new FeatureView(feature.id(), feature.name(), feature.description(), feature.paid(), feature.priceCents(), feature.termDays(),
                    feature.minVersion(), feature.dependencies(), feature.entryPath(), feature.capabilities(), status,
                    entitled, enabled, reason, purchased ? start : null, purchased ? expiry : null, managedByAuthority);
        }
        return new FeatureView(feature.id(), feature.name(), feature.description(), feature.paid(), feature.priceCents(), feature.termDays(), feature.minVersion(),
                feature.dependencies(), feature.entryPath(), feature.capabilities(), status, entitled, enabled, reason,
                null,
                claims != null && claims.features().contains(feature.id()) ? featureExpiry(claims, feature.id()) : null,
                false);
    }
    private Instant featureExpiry(PlatformLicense.Claims claims, String id) {
        return claims.featureExpiresAt() == null ? claims.expiresAt() : claims.featureExpiresAt().get(id);
    }
    private static Instant projectIssuedAt(JsonNode project) {
        try { return Instant.parse(project.path("issuedAt").asText()); }
        catch (Exception error) { throw new IllegalArgumentException("invalid project issuedAt", error); }
    }
    private static String projectLicenseId(JsonNode project) {
        var value = project.get("licenseId");
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("invalid project licenseId");
        try { return UUID.fromString(value.asText()).toString(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid project licenseId", error); }
    }
    private UpdateOption option(String id, String name, String description, Set<String> selected) {
        return new UpdateOption(id, name, description, selected == null || selected.contains(id));
    }
    private boolean domainAllowed(PlatformLicense.Claims claims, String requestDomain) {
        if (claims == null || claims.allowedDomains() == null || claims.allowedDomains().isEmpty()) return true;
        if (requestDomain == null || requestDomain.isBlank()) return false;
        String host = requestDomain.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\[([^]]+)](?::\\d+)?$", "$1")
                .replaceFirst(":\\d+$", "").replaceFirst("\\.$", "");
        return claims.allowedDomains().stream().anyMatch(allowed -> allowed.startsWith("*.")
                ? host.endsWith(allowed.substring(1)) && host.length() > allowed.length() - 1
                : host.equals(allowed));
    }
}
