package com.delta.admin.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentUpdateRegressionTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    JsonNode status(String currentRelease, String installedH5, String availableRelease) throws Exception {
        var properties = new PlatformProperties();
        properties.setStatePath(directory.resolve("state.json").toString());
        Files.writeString(directory.resolve("component-versions.json"), json.writeValueAsString(
                java.util.Map.of("release", currentRelease, "backend", availableRelease, "admin", availableRelease, "h5", installedH5)));
        Files.writeString(directory.resolve("authority.json"), """
                {"updates":{"releases":[{"version":"%s","items":[
                {"id":"backend","component":"backend"},{"id":"admin","component":"admin"},{"id":"h5","component":"h5"}]}]}}
                """.formatted(availableRelease));
        var platform = new PlatformService(properties, json, Clock.systemUTC());
        return new PlatformRemote(properties, platform, json, Clock.systemUTC()).status();
    }

    @Test void sameReleaseRemainsAvailableWhenOneComponentIsBehind() throws Exception {
        var result = status("1.4.0", "1.3.9", "1.4.0");
        assertThat(result.path("currentVersion").asText()).isEqualTo("1.4.0");
        assertThat(result.path("updates").path("updateAvailable").asBoolean()).isTrue();
        assertThat(result.path("updates").path("releases")).hasSize(1);
        assertThat(result.path("updates").path("releases").get(0).path("items")).hasSize(3);
    }

    @Test void anOlderOverallReleaseCanStillContainAnOutstandingComponentUpdate() throws Exception {
        var result = status("1.4.0", "1.3.8", "1.3.9");
        assertThat(result.path("updates").path("updateAvailable").asBoolean()).isTrue();
        assertThat(result.path("updates").path("releases").get(0).path("version").asText()).isEqualTo("1.3.9");
    }

    @Test void allCurrentComponentsSuppressAReleaseDespiteAnOlderOverallMarker() throws Exception {
        var result = status("1.3.9", "1.4.0", "1.4.0");
        assertThat(result.path("updates").path("updateAvailable").asBoolean()).isFalse();
        assertThat(result.path("updates").path("releases")).isEmpty();
    }
}
