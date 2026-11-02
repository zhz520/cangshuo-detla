package com.delta.admin.platform;

import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlatformServiceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-11-01T00:00:00Z");
    private KeyPair keys;
    private PlatformProperties properties;
    private PlatformService platform;

    @BeforeEach void setup() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var publicKey = directory.resolve("public.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        properties = new PlatformProperties(); properties.setCustomerId("742"); properties.setStatePath(directory.resolve("state.json").toString()); properties.setPublicKeyPath(publicKey.toString());
        platform = service(now);
    }
    private PlatformService service(Instant time) { return new PlatformService(properties, json, Clock.fixed(time, ZoneOffset.UTC)); }
    private ObjectNode project(Instant issuedAt, String licenseId) {
        var value = json.createObjectNode().put("issuedAt", issuedAt.toString());
        if (licenseId != null) value.put("licenseId", licenseId);
        return value;
    }
    private PlatformLicense.Envelope signed(String customer, String instance, Instant issuedAt, Instant start, Instant end, List<String> features) throws Exception {
        var claims = new PlatformLicense.Claims(UUID.randomUUID().toString(), customer, instance, issuedAt, start, end, features, null, null);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        var signature = Signature.getInstance("Ed25519"); signature.initSign(keys.getPrivate());
        signature.update((PlatformLicense.FORMAT + "." + payload).getBytes(StandardCharsets.UTF_8));
        return new PlatformLicense.Envelope(PlatformLicense.FORMAT, payload, Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }
    private PlatformLicense.Envelope grant() throws Exception {
        return signed("742", platform.overview().instanceId(), now, now, now.plusSeconds(3600), List.of("advanced_income_reports"));
    }
    private PlatformService.FeatureView report() { return platform.features().stream().filter(feature -> feature.id().equals("advanced_income_reports")).findFirst().orElseThrow(); }

    @Test void coreRemainsAvailableAndPaidModuleCannotBeEnabledWithoutALicense() {
        assertThat(platform.features().get(0).enabled()).isTrue(); assertThat(report().enabled()).isFalse();
        var official=platform.features().stream().filter(feature->feature.id().equals("official_wechat_notifications")).findFirst().orElseThrow();
        assertThat(official.paid()).isTrue(); assertThat(official.enabled()).isFalse(); assertThat(platform.featureEnabled("official_wechat_notifications")).isFalse();
        var email=platform.features().stream().filter(feature->feature.id().equals("email_notifications")).findFirst().orElseThrow();
        assertThat(email.paid()).isTrue(); assertThat(email.priceCents()).isEqualTo(5000); assertThat(email.minVersion()).isEqualTo("1.4.2");
        assertThat(email.entryPath()).isEqualTo("/system/email-notifications"); assertThat(email.enabled()).isFalse(); assertThat(email.termDays()).isEqualTo(365);
        var identity=platform.features().stream().filter(feature->feature.id().equals("identity_verification")).findFirst().orElseThrow();
        assertThat(identity.paid()).isTrue(); assertThat(identity.priceCents()).isEqualTo(2000); assertThat(identity.enabled()).isFalse();
        assertThatThrownBy(() -> platform.setEnabled("advanced_income_reports", true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.setEnabled("official_wechat_notifications", true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.requireFeature("advanced_income_reports")).isInstanceOf(BusinessException.class);
    }
    @Test void officialNotificationFeatureRequiresItsOwnPaidEntitlement() throws Exception {
        platform.importLicense(signed("742",platform.overview().instanceId(),now,now,now.plusSeconds(3600),List.of("official_wechat_notifications")));
        assertThat(platform.featureEnabled("official_wechat_notifications")).isFalse();
        platform.setEnabled("official_wechat_notifications",true);
        assertThat(platform.featureEnabled("official_wechat_notifications")).isTrue();
    }
    @Test void emailNotificationFeatureRequiresItsOwnPaidEntitlement() throws Exception {
        platform.importLicense(signed("742",platform.overview().instanceId(),now,now,now.plusSeconds(3600),List.of("email_notifications")));
        var email=platform.features().stream().filter(feature->feature.id().equals("email_notifications")).findFirst().orElseThrow();
        assertThat(email.entitled()).isTrue(); assertThat(email.enabled()).isFalse();
        platform.setEnabled("email_notifications",true);
        platform.requireFeature("email_notifications");
        assertThat(platform.featureEnabled("email_notifications")).isTrue();
    }
    @Test void signedGrantMustBeEnabledAndSurvivesRestartAndCanBeDisabled() throws Exception {
        String instance = platform.overview().instanceId(); platform.importLicense(grant());
        assertThat(report().entitled()).isTrue(); assertThat(report().enabled()).isFalse();
        platform.setEnabled("advanced_income_reports", true); platform = service(now);
        assertThat(platform.overview().instanceId()).isEqualTo(instance); platform.requireFeature("advanced_income_reports");
        platform.setEnabled("advanced_income_reports", false);
        assertThatThrownBy(() -> platform.requireFeature("advanced_income_reports")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsForgedCrossCustomerCrossInstanceAndUnknownFeatureGrants() throws Exception {
        var grant = grant();
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(new String(Base64.getUrlDecoder().decode(grant.payload()), StandardCharsets.UTF_8).replace("742", "743").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> platform.importLicense(new PlatformLicense.Envelope(grant.format(),changed,grant.signature()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.importLicense(signed("743",platform.overview().instanceId(),now,now,now.plusSeconds(60),List.of("advanced_income_reports")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.importLicense(signed("742",UUID.randomUUID().toString(),now,now,now.plusSeconds(60),List.of("advanced_income_reports")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.importLicense(signed("742",platform.overview().instanceId(),now,now,now.plusSeconds(60),List.of("not_a_feature")))).isInstanceOf(BusinessException.class);
        assertThat(platform.overview().licenseStatus()).isEqualTo("NONE");
    }
    @Test void expiredLicenseImmediatelyBlocksPaidApiButLeavesCoreAvailable() throws Exception {
        platform.importLicense(grant()); platform.setEnabled("advanced_income_reports", true); platform = service(now.plusSeconds(3600));
        assertThat(platform.overview().licenseStatus()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> platform.requireFeature("advanced_income_reports")).isInstanceOf(BusinessException.class);
        assertThat(platform.features().get(0).enabled()).isTrue();
    }
    @Test void futureGrantCannotBeEnabledBeforeItsStartAndOldGrantCannotReplaceNewerOne() throws Exception {
        var future = signed("742",platform.overview().instanceId(),now,now.plusSeconds(60),now.plusSeconds(3600),List.of("advanced_income_reports"));
        platform.importLicense(future);
        assertThat(platform.overview().licenseStatus()).isEqualTo("NOT_YET_VALID");
        assertThatThrownBy(() -> platform.setEnabled("advanced_income_reports",true)).isInstanceOf(BusinessException.class);
        platform = service(now.plusSeconds(61));
        platform.setEnabled("advanced_income_reports",true);
        assertThatThrownBy(() -> platform.importLicense(signed("742",platform.overview().instanceId(),now.minusSeconds(1),now,now.plusSeconds(3600),List.of("advanced_income_reports")))).isInstanceOf(BusinessException.class);
    }
    @Test void localSwitchTamperingDoesNotGrantAccessAndCorruptStateIsNotRecreated() throws Exception {
        platform.overview();
        Path state = Path.of(properties.getStatePath());
        var original = Files.readString(state);
        Files.writeString(state, original.replace("\"enabledFeatures\":[]", "\"enabledFeatures\":[\"advanced_income_reports\"]"));
        assertThatThrownBy(() -> platform.requireFeature("advanced_income_reports")).isInstanceOf(BusinessException.class);
        Files.writeString(state,"broken");
        assertThatThrownBy(() -> platform.overview()).isInstanceOf(BusinessException.class);
        assertThat(Files.readString(state)).isEqualTo("broken");
    }
    @Test void missingPublicKeyCannotTurnIntoATrialOrSelfIssuedLicense() throws Exception {
        var grant = grant(); properties.setPublicKeyPath("");
        assertThatThrownBy(() -> platform.importLicense(grant)).isInstanceOf(BusinessException.class);
        assertThat(report().entitled()).isFalse(); assertThat(platform.updates().automaticUpdateAvailable()).isFalse();
    }
    @Test void updateContentSelectionIsValidatedPersistedAndDefaultsSafely() {
        assertThat(platform.updates().components()).allMatch(PlatformService.UpdateOption::selected);
        platform.setUpdateComponents(List.of("core_platform", "admin_console")); platform = service(now);
        assertThat(platform.updates().components()).filteredOn(PlatformService.UpdateOption::selected)
                .extracting(PlatformService.UpdateOption::id).containsExactly("core_platform","admin_console");
        assertThatThrownBy(() -> platform.setUpdateComponents(List.of("unknown"))).isInstanceOf(BusinessException.class);
        platform.setUpdateComponents(List.of());
        assertThat(platform.updates().components()).noneMatch(PlatformService.UpdateOption::selected);
    }
    @Test void individualModuleExpiresEvenWhenTheEnvelopeHasALaterExpiration() throws Exception {
        var claims = new PlatformLicense.Claims(UUID.randomUUID().toString(), "742", platform.overview().instanceId(),
                now, now, now.plusSeconds(3600), List.of("advanced_income_reports"), Map.of("advanced_income_reports", now.plusSeconds(60)), null);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        var signature = Signature.getInstance("Ed25519"); signature.initSign(keys.getPrivate());
        signature.update((PlatformLicense.FORMAT + "." + payload).getBytes(StandardCharsets.UTF_8));
        platform.importLicense(new PlatformLicense.Envelope(PlatformLicense.FORMAT, payload, Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())));
        platform.setEnabled("advanced_income_reports", true); platform = service(now.plusSeconds(61));
        assertThat(platform.overview().licenseStatus()).isEqualTo("VALID");
        assertThat(report().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> platform.requireFeature("advanced_income_reports")).isInstanceOf(BusinessException.class);
    }
    @Test void invalidReplacementLeavesPreviousLicenseAndSettingsIntact() throws Exception {
        platform.importLicense(grant()); platform.setEnabled("advanced_income_reports",true);
        String before = Files.readString(Path.of(properties.getStatePath()));
        assertThatThrownBy(() -> platform.importLicense(new PlatformLicense.Envelope("bad","bad","bad"))).isInstanceOf(BusinessException.class);
        assertThat(Files.readString(Path.of(properties.getStatePath()))).isEqualTo(before); platform.requireFeature("advanced_income_reports");
    }

    @Test void remoteFeatureMapReplacesLocalSwitchesAndDefaultsEveryPaidFeatureOff() throws Exception {
        platform.importLicense(grant()); platform.setEnabled("advanced_income_reports", true);
        assertThat(report().enabled()).isTrue();

        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null), Map.of(), Map.of(), Map.of());

        assertThat(report().managedByAuthority()).isTrue(); assertThat(report().enabled()).isFalse();
        assertThat(platform.features().stream().filter(PlatformService.FeatureView::paid))
                .allMatch(PlatformService.FeatureView::managedByAuthority).allMatch(feature -> !feature.enabled());
        assertThatThrownBy(() -> platform.setEnabled("advanced_income_reports", true))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error).hasMessage("功能启用状态由授权中心管理");
                });
    }

    @Test void remoteEnabledStillRequiresAnUnexpiredEntitlement() throws Exception {
        platform.importLicense(grant());
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null), Map.of("advanced_income_reports", true), Map.of(), Map.of());
        assertThat(report().entitled()).isTrue(); assertThat(report().enabled()).isTrue();
        assertThat(report().startsAt()).isEqualTo(now); assertThat(report().expiresAt()).isEqualTo(now.plusSeconds(3600));

        platform = service(now.plusSeconds(3600));
        assertThat(report().status()).isEqualTo("EXPIRED"); assertThat(report().enabled()).isFalse();
        assertThat(report().managedByAuthority()).isTrue();
    }

    @Test void remoteFeatureStillEnforcesTheRequestedLicensedDomain() throws Exception {
        var claims = new PlatformLicense.Claims(UUID.randomUUID().toString(), "742", platform.overview().instanceId(),
                now, now, now.plusSeconds(3600), List.of("email_notifications"), null,
                List.of("admin.example.com"));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        var signature = Signature.getInstance("Ed25519"); signature.initSign(keys.getPrivate());
        signature.update((PlatformLicense.FORMAT + "." + payload).getBytes(StandardCharsets.UTF_8));
        platform.importLicense(new PlatformLicense.Envelope(PlatformLicense.FORMAT, payload,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())));
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null), Map.of("email_notifications", true),
                Map.of("email_notifications", now), Map.of("email_notifications", now.plusSeconds(3600)));

        platform.requireFeature("email_notifications", "admin.example.com");
        assertThatThrownBy(() -> platform.requireFeature("email_notifications"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> platform.requireFeature("email_notifications", "other.example.com"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void legacyStateWithoutRemoteFieldsKeepsTheLocalSwitchBehavior() throws Exception {
        platform.importLicense(grant()); platform.setEnabled("advanced_income_reports", true);
        Path state = Path.of(properties.getStatePath());
        var legacy = (ObjectNode) json.readTree(state.toFile());
        legacy.remove("remoteFeatureEnabled"); legacy.remove("remoteFeatureStartsAt"); legacy.remove("remoteFeatureExpiresAt");
        Files.writeString(state, json.writeValueAsString(legacy));

        platform = service(now);
        assertThat(report().managedByAuthority()).isFalse(); assertThat(report().enabled()).isTrue();
        platform.setEnabled("advanced_income_reports", false);
        assertThat(report().enabled()).isFalse();
    }

    @Test void authoritySnapshotKeepsUnknownIdsAndClearsEveryRemoteMapTogether() throws Exception {
        platform.importLicense(grant());
        platform.setEnabled("advanced_income_reports", true);
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null),
                Map.of("advanced_income_reports", false, "future_module", false),
                Map.of("advanced_income_reports", now.minusSeconds(60), "future_module", now),
                Map.of("advanced_income_reports", now.plusSeconds(600), "future_module", now.plusSeconds(120)));

        var state = json.readTree(Path.of(properties.getStatePath()).toFile());
        assertThat(state.path("remoteFeatureEnabled").has("future_module")).isTrue();
        assertThat(Instant.ofEpochSecond(state.path("remoteFeatureStartsAt").path("future_module").asLong()))
                .isEqualTo(now);
        assertThat(state.path("remoteFeatureExpiresAt").has("future_module")).isTrue();
        assertThat(report().managedByAuthority()).isTrue(); assertThat(report().enabled()).isFalse();
        assertThat(report().startsAt()).isEqualTo(now.minusSeconds(60));
        assertThat(report().expiresAt()).isEqualTo(now.plusSeconds(600));

        assertThatThrownBy(() -> platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(2), null), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        state = json.readTree(Path.of(properties.getStatePath()).toFile());
        assertThat(state.path("remoteFeatureEnabled").has("future_module")).isTrue();
        assertThat(state.path("remoteFeatureStartsAt").has("future_module")).isTrue();
        assertThat(state.path("remoteFeatureExpiresAt").has("future_module")).isTrue();
        assertThat(report().managedByAuthority()).isTrue(); assertThat(report().enabled()).isFalse();
    }

    @Test void remoteCurrentEntitlementShowsDisabledPeriodEvenWhenAbsentFromOldLicense() throws Exception {
        platform.importLicense(signed("742", platform.overview().instanceId(), now, now, now.plusSeconds(3600), List.of()));
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null), Map.of("advanced_income_reports", false),
                Map.of("advanced_income_reports", now.minusSeconds(120)),
                Map.of("advanced_income_reports", now.plusSeconds(600)));

        assertThat(report().entitled()).isTrue();
        assertThat(report().enabled()).isFalse();
        assertThat(report().managedByAuthority()).isTrue();
        assertThat(report().startsAt()).isEqualTo(now.minusSeconds(120));
        assertThat(report().expiresAt()).isEqualTo(now.plusSeconds(600));
        assertThat(report().status()).isEqualTo("DISABLED");
    }

    @Test void authoritySnapshotRejectsPeriodsWhoseStartIsNotBeforeExpiry() throws Exception {
        platform.overview();
        var before = Files.readString(Path.of(properties.getStatePath()));
        assertThatThrownBy(() -> platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null),
                Map.of("advanced_income_reports", true),
                Map.of("advanced_income_reports", now.plusSeconds(60)),
                Map.of("advanced_income_reports", now)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(Path.of(properties.getStatePath()))).isEqualTo(before);
    }
    @Test void remoteEnablementCannotInventAFeatureMissingFromTheLicense() throws Exception {
        platform.importLicense(grant());
        assertThatThrownBy(() -> platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(1), null),
                Map.of("email_notifications", true), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(platform.featureEnabled("email_notifications")).isFalse();
    }
    @Test void projectAndLicenseSnapshotMustReferenceTheSameLicense() throws Exception {
        var envelope = grant();
        assertThatThrownBy(() -> platform.applyVerifiedAuthoritySnapshot(envelope, project(now.plusSeconds(1), UUID.randomUUID().toString()),
                Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report().entitled()).isFalse();
    }
    @Test void manuallyImportingALicenseCannotBypassAManagedSnapshot() throws Exception {
        platform.importLicense(grant());
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(20), null),
                Map.of("advanced_income_reports", false), Map.of(), Map.of());
        String accepted = Files.readString(Path.of(properties.getStatePath()));
        assertThatThrownBy(() -> platform.importLicense(grant()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error).hasMessageContaining("授权中心托管");
                });
        assertThat(Files.readString(Path.of(properties.getStatePath()))).isEqualTo(accepted);
    }
    @Test void olderProjectSnapshotsCannotOverwriteSecurityState() throws Exception {
        platform.importLicense(grant());
        platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(20), null),
                Map.of("advanced_income_reports", false), Map.of(), Map.of());
        String accepted = Files.readString(Path.of(properties.getStatePath()));
        assertThatThrownBy(() -> platform.applyVerifiedAuthoritySnapshot(null, project(now.plusSeconds(10), null),
                Map.of("advanced_income_reports", true), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(Path.of(properties.getStatePath()))).isEqualTo(accepted);
        assertThat(platform.remoteProject().path("issuedAt").asText()).isEqualTo(now.plusSeconds(20).toString());
    }
    @Test void signedEmptyLicenseRevokesEveryPayableFeatureWithoutAffectingCore() throws Exception {
        platform.importLicense(signed("742",platform.overview().instanceId(),now,now,now.plusSeconds(3600),List.of()));
        assertThat(platform.featureEnabled("advanced_income_reports")).isFalse();
        assertThat(platform.featureEnabled("email_notifications")).isFalse();
        assertThat(platform.features()).filteredOn(feature -> !feature.paid()).allMatch(PlatformService.FeatureView::enabled);
        assertThat(platform.features()).filteredOn(PlatformService.FeatureView::paid).allMatch(feature -> !feature.entitled());
    }
}
