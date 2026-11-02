package com.delta.admin.platform;

import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRemoteTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void latestSuccessfulReleaseWinsOverOlderPlansAndIgnoresMalformedFiles() throws Exception {
        Files.writeString(directory.resolve("first.json"), "{\"targetVersion\":\"1.3.1\"}");
        Files.writeString(directory.resolve("second.json"), "{\"targetVersion\":\"1.3.2\"}");
        Files.writeString(directory.resolve("broken.json"), "not-json");
        assertThat(PlatformRemote.latestInstalledRelease(directory, json)).isEqualTo("1.3.2");
    }

    @Test void noSuccessfulPlanHasNoReleaseMarker() {
        assertThat(PlatformRemote.latestInstalledRelease(directory, json)).isNull();
    }

    @Test void cachedUpdateCatalogIsFilteredAgainstTheCurrentVersion() {
        var properties=new PlatformProperties();properties.setCustomerId("742");properties.setStatePath(directory.resolve("state.json").toString());properties.setPublicKeyPath(directory.resolve("public.pem").toString());
        var remote=new PlatformRemote(properties,null,json,java.time.Clock.systemUTC());
        ObjectNode cached=json.createObjectNode();cached.put("currentVersion","1.3.4");cached.put("latestVersion","1.3.5");cached.put("updateAvailable",true);cached.set("releases",json.createArrayNode().add(json.createObjectNode().put("version","1.3.5")));
        var current=remote.currentUpdates(cached,"1.3.5");
        assertThat(current.path("currentVersion").asText()).isEqualTo("1.3.5");assertThat(current.path("updateAvailable").asBoolean()).isFalse();assertThat(current.path("releases")).isEmpty();
        var pending=remote.currentUpdates(cached,"1.3.4");
        assertThat(pending.path("updateAvailable").asBoolean()).isTrue();assertThat(pending.path("releases")).hasSize(1);
    }

    @Test void cachedCommercialModulesProvidePlatformConfiguredPricing() throws Exception {
        var properties=new PlatformProperties();properties.setCustomerId("742");properties.setStatePath(directory.resolve("state.json").toString());properties.setPublicKeyPath(directory.resolve("public.pem").toString());
        Files.writeString(directory.resolve("authority.json"), "{\"modules\":[{\"id\":\"identity_verification\",\"priceCents\":2000,\"minVersion\":\"1.4.0\"}]}");
        var remote=new PlatformRemote(properties,null,json,java.time.Clock.systemUTC());
        var module=remote.commercialModules().get("identity_verification");
        assertThat(module.path("priceCents").asInt()).isEqualTo(2000);
        assertThat(module.path("minVersion").asText()).isEqualTo("1.4.0");
    }

    @Test void signedFeatureMapsPreserveLegacyClaimsAndRejectInvalidTypesAndTimes() throws Exception {
        assertThat(PlatformRemote.remoteFeatureEnabled(json.createObjectNode())).isNull();
        assertThat(PlatformRemote.remoteFeatureTimes(json.createObjectNode(), "featureStartsAt")).isNull();
        var claims = json.createObjectNode();
        claims.set("featureEnabled", json.createObjectNode().put("advanced_income_reports", true)
                .put("email_notifications", false));
        assertThat(PlatformRemote.remoteFeatureEnabled(claims)).containsExactlyInAnyOrderEntriesOf(
                Map.of("advanced_income_reports", true, "email_notifications", false));
        claims.set("featureEnabled", json.createObjectNode().put("advanced_income_reports", "true"));
        assertThatThrownBy(() -> PlatformRemote.remoteFeatureEnabled(claims)).isInstanceOf(IllegalArgumentException.class);
        claims.set("featureStartsAt", json.createObjectNode().put("advanced_income_reports", "2026-11-01T00:00:00Z"));
        assertThat(PlatformRemote.remoteFeatureTimes(claims, "featureStartsAt").get("advanced_income_reports"))
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        claims.set("featureStartsAt", json.createObjectNode().put("advanced_income_reports", "not-a-time"));
        assertThatThrownBy(() -> PlatformRemote.remoteFeatureTimes(claims, "featureStartsAt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refreshAppliesTheMapOnlyFromASignedProjectClaim() throws Exception {
        var serviceJson = new ObjectMapper().findAndRegisterModules();
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var publicKey = directory.resolve("public.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        var properties = new PlatformProperties(); properties.setCustomerId("742");
        properties.setStatePath(directory.resolve("state.json").toString()); properties.setPublicKeyPath(publicKey.toString());
        Instant now = Instant.parse("2026-11-01T00:00:00Z"); Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var platform = new PlatformService(properties, serviceJson, clock);
        platform.importLicense(license(keys, serviceJson, platform.overview().instanceId(), now));
        platform.setEnabled("advanced_income_reports", true);

        var validProject = new AtomicBoolean(true);
        var includeFeatureMap = new AtomicBoolean(false);
        var projectSequence = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/report", exchange -> {
            try {
                var projectClaims = serviceJson.createObjectNode();
                projectClaims.put("customerId", "742"); projectClaims.put("instanceId", platform.overview().instanceId());
                projectClaims.put("issuedAt", now.plusSeconds(projectSequence.incrementAndGet()).toString());
                if (includeFeatureMap.get()) {
                    projectClaims.set("featureEnabled",
                            serviceJson.createObjectNode().put("advanced_income_reports", false));
                    projectClaims.set("featureStartsAt", serviceJson.createObjectNode()
                            .put("advanced_income_reports", now.minusSeconds(60).toString()));
                    projectClaims.set("featureExpiresAt", serviceJson.createObjectNode()
                            .put("advanced_income_reports", now.plusSeconds(600).toString()));
                }
                var project = signed("cangshuo-project-v1", projectClaims, keys, serviceJson);
                if (!validProject.get()) {
                    String signature = project.path("signature").asText();
                    project.put("signature", (signature.startsWith("A") ? "B" : "A") + signature.substring(1));
                }
                var response = serviceJson.createObjectNode(); response.set("project", project);
                response.set("modules", serviceJson.createArrayNode().add(serviceJson.createObjectNode()
                        .put("id", "email_notifications").put("priceCents", 6200).put("termDays", 45)
                        .put("minVersion", "1.4.2")));
                byte[] body = serviceJson.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            properties.setReportUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/report");
            new PlatformRemote(properties, platform, serviceJson, clock).refresh();
            assertThat(platform.features().stream().filter(item -> item.id().equals("advanced_income_reports")).findFirst().orElseThrow())
                    .matches(item -> item.managedByAuthority() == false && item.enabled() == true);

            includeFeatureMap.set(true);
            new PlatformRemote(properties, platform, serviceJson, clock).refresh();
            var advanced = platform.features().stream().filter(item -> item.id().equals("advanced_income_reports")).findFirst().orElseThrow();
            assertThat(advanced.managedByAuthority()).isTrue(); assertThat(advanced.enabled()).isFalse();
            assertThat(advanced.startsAt()).isEqualTo(now.minusSeconds(60));
            assertThat(advanced.expiresAt()).isEqualTo(now.plusSeconds(600));
            assertThat(platform.features().stream().filter(PlatformService.FeatureView::paid)).allMatch(PlatformService.FeatureView::managedByAuthority);
            var controller = new PlatformController(null, new PlatformRemote(properties, platform, serviceJson, clock), null);
            var email = controller.withCommercialMetadata(platform.features()).stream()
                    .filter(item -> item.id().equals("email_notifications")).findFirst().orElseThrow();
            assertThat(email.priceCents()).isEqualTo(6200); assertThat(email.termDays()).isEqualTo(45);
            Files.deleteIfExists(directory.resolve("authority.json"));
            assertThat(new PlatformRemote(properties, platform, serviceJson, clock).project().path("instanceId").asText())
                    .isEqualTo(platform.overview().instanceId());

            includeFeatureMap.set(false);
            assertThatThrownBy(() -> new PlatformRemote(properties, platform, serviceJson, clock).refresh())
                    .isInstanceOf(BusinessException.class);
            String stateBeforeInvalidResponse = Files.readString(Path.of(properties.getStatePath()));

            includeFeatureMap.set(true);
            validProject.set(false);
            assertThatThrownBy(() -> new PlatformRemote(properties, platform, serviceJson, clock).refresh())
                    .isInstanceOf(BusinessException.class);
            assertThat(Files.readString(Path.of(properties.getStatePath()))).isEqualTo(stateBeforeInvalidResponse);
        } finally { server.stop(0); }
    }

    private PlatformLicense.Envelope license(KeyPair keys, ObjectMapper mapper, String instanceId, Instant now) throws Exception {
        var claims = new PlatformLicense.Claims(UUID.randomUUID().toString(), "742", instanceId, now, now,
                now.plusSeconds(3600), List.of("advanced_income_reports"), null, null);
        var envelope = signed("delta-license-v1", mapper.valueToTree(claims), keys, mapper);
        return mapper.treeToValue(envelope, PlatformLicense.Envelope.class);
    }

    private ObjectNode signed(String format, JsonNode claims, KeyPair keys, ObjectMapper mapper) throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));
        var signature = Signature.getInstance("Ed25519"); signature.initSign(keys.getPrivate());
        signature.update((format + "." + payload).getBytes(StandardCharsets.UTF_8));
        return mapper.createObjectNode().put("format", format).put("payload", payload)
                .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }
}
