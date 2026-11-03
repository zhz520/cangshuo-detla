package com.delta.common.file.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailCacheCleanerTest {
    @TempDir Path uploads;

    @Test
    void removesExpiredOversizedAndAbandonedFilesWhileKeepingRecentDerivatives() throws Exception {
        Path cache = Files.createDirectories(uploads.resolve(".thumbnails"));
        String recentName = "a".repeat(64) + ".jpg";
        String expiredName = "b".repeat(64) + ".png";
        String oversizedName = "c".repeat(64) + ".jpg";
        Path recent = Files.write(cache.resolve(recentName), new byte[3]);
        Path expired = Files.write(cache.resolve(expiredName), new byte[5]);
        Path oversized = Files.write(cache.resolve(oversizedName), new byte[9]);
        Path abandoned = Files.write(cache.resolve("encoding-abandoned.tmp"), new byte[2]);
        Files.setLastModifiedTime(oversized, FileTime.from(Instant.now().minus(Duration.ofMinutes(1))));
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofDays(60))));
        Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minus(Duration.ofHours(8))));

        var result = ThumbnailCacheCleaner.cleanup(uploads, Duration.ofDays(30), 4);

        assertThat(recent).exists();
        assertThat(expired).doesNotExist();
        assertThat(oversized).doesNotExist();
        assertThat(abandoned).doesNotExist();
        assertThat(result.retained()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(3);
        assertThat(result.retainedBytes()).isEqualTo(3);
        assertThat(result.removedBytes()).isEqualTo(16);
    }

    @Test
    void ignoresUnexpectedNamesAndSymlinks() throws Exception {
        Path cache = Files.createDirectories(uploads.resolve(".thumbnails"));
        Path unrelated = Files.writeString(cache.resolve("keep.txt"), "not a derivative");

        var result = ThumbnailCacheCleaner.cleanup(uploads, Duration.ZERO, 0);

        assertThat(unrelated).exists();
        assertThat(result.removed()).isZero();
    }
}
