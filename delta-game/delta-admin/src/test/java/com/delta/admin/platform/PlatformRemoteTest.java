package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
