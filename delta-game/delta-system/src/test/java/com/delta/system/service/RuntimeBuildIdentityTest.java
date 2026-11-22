package com.delta.system.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeBuildIdentityTest {
    @TempDir
    Path directory;

    @Test
    void acceptsPlainAndSpringBootNestedJarLocations() throws Exception {
        Path jar = directory.resolve("delta-admin.jar");
        Files.write(jar, new byte[]{1, 2, 3});
        assertThat(RuntimeBuildIdentity.jarFromLocation(jar.toUri().toURL())).isEqualTo(jar.toAbsolutePath());
        URL nested = new URL("jar:" + jar.toUri() + "!/BOOT-INF/classes!/");
        assertThat(RuntimeBuildIdentity.jarFromLocation(nested)).isEqualTo(jar.toAbsolutePath());
    }

    @Test
    void ignoresDirectoriesAndStableHashesFileContent() throws Exception {
        assertThat(RuntimeBuildIdentity.jarFromLocation(directory.toUri().toURL())).isNull();
        Path jar = directory.resolve("delta-admin.jar");
        Files.write(jar, new byte[]{4, 5, 6});
        String first = RuntimeBuildIdentity.sha256(jar);
        assertThat(first).matches("[a-f0-9]{64}").isEqualTo(RuntimeBuildIdentity.sha256(jar));
        Files.write(jar, new byte[]{7, 8, 9});
        assertThat(RuntimeBuildIdentity.sha256(jar)).isNotEqualTo(first);
    }

    @Test
    void prefersTheSingleLauncherJarOverNestedDependencySource() throws Exception {
        Path launcher = directory.resolve("app.jar");
        Path nested = directory.resolve("delta-system.jar");
        Files.write(launcher, new byte[]{1});
        Files.write(nested, new byte[]{2});
        String original = System.getProperty("java.class.path", "");
        try {
            System.setProperty("java.class.path", launcher.toString());
            assertThat(RuntimeBuildIdentity.executableJar(nested.toUri().toURL()))
                    .isEqualTo(launcher.toAbsolutePath());
        } finally {
            System.setProperty("java.class.path", original);
        }
    }
}
