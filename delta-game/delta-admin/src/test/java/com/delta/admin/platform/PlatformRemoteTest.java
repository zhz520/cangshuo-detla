package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

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
}
