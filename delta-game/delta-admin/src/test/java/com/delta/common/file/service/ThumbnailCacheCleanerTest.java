package com.delta.common.file.service;

import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void ignoresUnexpectedNamesAndPrivateOrNestedFiles() throws Exception {
        Path cache = Files.createDirectories(uploads.resolve(".thumbnails"));
        Path unrelated = Files.writeString(cache.resolve("keep.txt"), "not a derivative");
        Path privateFile = oldDerivative(uploads.resolve("private/wxpay"), 'd');
        Path matchingDirectory = Files.createDirectory(cache.resolve("e".repeat(64) + ".jpg"));
        Path nested = oldDerivative(matchingDirectory, 'f');

        var result = ThumbnailCacheCleaner.cleanup(uploads, Duration.ZERO, 0);

        assertThat(unrelated).exists();
        assertThat(privateFile).exists();
        assertThat(nested).exists();
        assertThat(result.removed()).isZero();
    }

    @Test
    void continuesAfterFirstTenantCacheReadFails() throws Exception {
        Path blocked = oldDerivative(uploads.resolve("first/.thumbnails"), 'a');
        Path healthy = oldDerivative(uploads.resolve("second/.thumbnails"), 'b');
        Path unscoped = oldDerivative(uploads.resolve(".thumbnails"), 'c');
        Path blockedCache = blocked.getParent().toRealPath();
        var cleaner = sharedCleaner();

        withUnreadableDirectory(blockedCache, cleaner::scheduledCleanup);

        assertThat(blocked).exists();
        assertThat(healthy).doesNotExist();
        assertThat(unscoped).exists();
    }

    @Test
    void skipsTenantRootLinkWithoutTouchingPrivateFiles() throws Exception {
        Path privateRoot = uploads.resolve("private/wxpay");
        Path privateFile = oldDerivative(privateRoot.resolve(".thumbnails"), 'a');
        Path healthy = oldDerivative(uploads.resolve("second/.thumbnails"), 'b');
        link(uploads.resolve("first"), privateRoot);

        sharedCleaner().scheduledCleanup();

        assertThat(privateFile).exists();
        assertThat(healthy).doesNotExist();
    }

    @Test
    void skipsCacheDirectoryLinkWithoutTouchingItsTarget() throws Exception {
        Path privateFile = oldDerivative(uploads.resolve("private/wxpay"), 'a');
        Path first = Files.createDirectory(uploads.resolve("first"));
        Path healthy = oldDerivative(uploads.resolve("second/.thumbnails"), 'b');
        Path cacheLink = first.resolve(".thumbnails");
        link(cacheLink, privateFile.getParent());

        sharedCleaner().scheduledCleanup();

        assertThat(privateFile).exists();
        assertThat(Files.isSymbolicLink(cacheLink)).isTrue();
        assertThat(healthy).doesNotExist();
    }

    @Test
    void skipsMatchingSymbolicLinkButStillRemovesRealDerivative() throws Exception {
        Path privateFile = oldDerivative(uploads.resolve("private/wxpay"), 'a');
        Path derivative = oldDerivative(uploads.resolve(".thumbnails"), 'b');
        Path link = derivative.resolveSibling("c".repeat(64) + ".jpg");
        link(link, privateFile);

        var result = ThumbnailCacheCleaner.cleanup(uploads, Duration.ofDays(30), 1024);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(privateFile).exists();
        assertThat(derivative).doesNotExist();
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.removedBytes()).isEqualTo(1);
    }

    @Test
    void neverCleansReservedPrivateDirectoriesInSharedMode() throws Exception {
        Path paymentFile = oldDerivative(uploads.resolve("private/.thumbnails"), 'a');
        Path legacyPaymentFile = oldDerivative(uploads.resolve("wxpay/.thumbnails"), 'b');
        Path healthy = oldDerivative(uploads.resolve("second/.thumbnails"), 'c');

        sharedCleaner("private", "wxpay", "second").scheduledCleanup();

        assertThat(paymentFile).exists();
        assertThat(legacyPaymentFile).exists();
        assertThat(healthy).doesNotExist();
    }

    @Test
    void ignoresLinkedUploadRootInSingleTenantMode() throws Exception {
        Path target = Files.createDirectory(uploads.resolve("target"));
        Path derivative = oldDerivative(target.resolve(".thumbnails"), 'a');
        Path linkedRoot = uploads.resolve("alias");
        link(linkedRoot, target);

        var result = ThumbnailCacheCleaner.cleanup(linkedRoot, Duration.ofDays(30), 1024);

        assertThat(derivative).exists();
        assertThat(result.removed()).isZero();
    }

    private static void withUnreadableDirectory(Path directory, Runnable action) throws IOException {
        var posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        if (posix != null) {
            var original = posix.readAttributes().permissions();
            try {
                posix.setPermissions(Set.of(PosixFilePermission.OWNER_EXECUTE));
                runIfListingDenied(directory, action);
            } finally {
                posix.setPermissions(original);
            }
            return;
        }

        var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        assumeTrue(acl != null, "No POSIX or ACL permissions on the test filesystem");
        var original = acl.getAcl();
        var restricted = new ArrayList<>(original);
        restricted.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                .setPrincipal(Files.getOwner(directory))
                .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
        try {
            acl.setAcl(restricted);
            runIfListingDenied(directory, action);
        } finally {
            acl.setAcl(original);
        }
    }

    private static void runIfListingDenied(Path directory, Runnable action) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            assumeTrue(false, "Test process can still list the restricted directory");
        } catch (AccessDeniedException expected) {
            action.run();
        }
    }

    private ThumbnailCacheCleaner sharedCleaner() {
        return sharedCleaner("first", "second");
    }

    private ThumbnailCacheCleaner sharedCleaner(String... ids) {
        var properties = new TenantProperties();
        properties.setEnabled(true);
        for (String id : ids) {
            var tenant = new TenantProperties.Tenant();
            tenant.setHosts(List.of(id + ".example.test"));
            properties.getTenants().put(id, tenant);
        }
        var registry = new TenantRegistry(properties);
        var orderedRegistry = mock(TenantRegistry.class);
        when(orderedRegistry.activeTenants()).thenReturn(
                Arrays.stream(ids).map(id -> registry.findById(id).orElseThrow()).toList());
        var cleaner = new ThumbnailCacheCleaner();
        ReflectionTestUtils.setField(cleaner, "uploadPath", uploads.toString());
        ReflectionTestUtils.setField(cleaner, "retentionDays", 30L);
        ReflectionTestUtils.setField(cleaner, "maxSizeMb", 1L);
        ReflectionTestUtils.setField(cleaner, "tenantProperties", properties);
        ReflectionTestUtils.setField(cleaner, "tenantRegistry", orderedRegistry);
        return cleaner;
    }

    private static Path oldDerivative(Path directory, char digest) throws IOException {
        Path file = Files.write(Files.createDirectories(directory).resolve(String.valueOf(digest).repeat(64) + ".jpg"),
                new byte[1]);
        return Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(60))));
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException error) {
            assumeTrue(false, "Symbolic links unavailable: " + error.getClass().getSimpleName());
        }
    }
}
