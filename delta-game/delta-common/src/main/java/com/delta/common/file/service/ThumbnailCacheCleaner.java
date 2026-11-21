package com.delta.common.file.service;

import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounds derivative files created by ImageThumbnailController. */
@Slf4j
@Component
public class ThumbnailCacheCleaner {
    private static final Pattern THUMBNAIL = Pattern.compile("[a-f0-9]{64}\\.(?:jpg|png)");
    private static final Pattern TEMPORARY = Pattern.compile("encoding-[^/]+" + Pattern.quote(".tmp"));
    private static final Set<String> PRIVATE_STORAGE = Set.of("private", "wxpay");
    private static final Result EMPTY = new Result(0, 0, 0, 0);

    @Value("${file.upload.path:/data/upload}")
    private String uploadPath;
    @Value("${file.thumbnail.retention-days:45}")
    private long retentionDays;
    @Value("${file.thumbnail.max-size-mb:2048}")
    private long maxSizeMb;
    @Autowired(required = false) private TenantProperties tenantProperties;
    @Autowired(required = false) private TenantRegistry tenantRegistry;

    public record Result(int retained, int removed, long retainedBytes, long removedBytes) {}

    private record Candidate(Path path, BasicFileAttributes attributes, boolean temporary) {}

    @Scheduled(cron = "${file.thumbnail.cleanup-cron:0 20 4 * * *}")
    public void scheduledCleanup() {
        try {
            long maxBytes = Math.max(1, maxSizeMb) * 1024 * 1024;
            Duration age = Duration.ofDays(Math.max(1, retentionDays));
            if (tenantProperties != null && tenantProperties.isEnabled()) {
                if (tenantRegistry == null) {
                    log.warn("共享模式租户注册表不可用，跳过缩略图缓存清理");
                    return;
                }
                Path root = configuredRoot(Path.of(uploadPath));
                if (root == null) return;
                for (var tenant : tenantRegistry.activeTenants()) {
                    String tenantId = tenant.id();
                    if (PRIVATE_STORAGE.contains(tenantId)) continue;
                    try {
                        Path tenantRoot = root.resolve(tenantId);
                        if (directDirectory(tenantRoot) == null) continue;
                        Result result = cleanupAt(tenantRoot, age, maxBytes);
                        if (result.removed() > 0) log.info("租户[{}]缩略图缓存清理完成: retained={}, removed={}, freedBytes={}",
                                tenantId, result.retained(), result.removed(), result.removedBytes());
                    } catch (IOException | RuntimeException error) {
                        log.warn("租户[{}]缩略图缓存清理失败: {}", tenantId, error.getClass().getSimpleName());
                    }
                }
            } else {
                Result result = cleanup(Path.of(uploadPath), age, maxBytes);
                if (result.removed() > 0) log.info("缩略图缓存清理完成: retained={}, removed={}, freedBytes={}",
                        result.retained(), result.removed(), result.removedBytes());
            }
        } catch (Exception error) {
            log.warn("缩略图缓存清理失败: {}", error.getClass().getSimpleName());
        }
    }

    public static Result cleanup(Path uploadRoot, Duration maxAge, long maxBytes) throws IOException {
        Path root = configuredRoot(uploadRoot);
        return root == null ? EMPTY : cleanupAt(root, maxAge, maxBytes);
    }

    private static Path configuredRoot(Path uploadRoot) throws IOException {
        if (uploadRoot == null) return null;
        try {
            Path expected = uploadRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            return directDirectory(expected) == null ? null : expected;
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private static BasicFileAttributes directDirectory(Path expected) throws IOException {
        try {
            var attributes = Files.readAttributes(expected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) return null;
            // Compare the full path: a Windows junction can report itself as a directory without following links.
            if (!expected.equals(expected.toRealPath())) throw new IOException("thumbnail directory redirected");
            return attributes;
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private static Result cleanupAt(Path root, Duration maxAge, long maxBytes) throws IOException {
        Path cache = root.resolve(".thumbnails");
        var directory = directDirectory(cache);
        if (directory == null) return EMPTY;
        try (var files = Files.newDirectoryStream(cache)) {
            // Where supported, pin reads and deletes to the verified directory handle.
            var secure = files instanceof SecureDirectoryStream<Path> stream ? stream : null;
            if (secure != null) {
                var opened = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
                if (!Objects.equals(directory.fileKey(), opened.fileKey())) {
                    throw new IOException("thumbnail directory changed");
                }
            } else if (directDirectory(cache) == null) {
                return EMPTY;
            }
            return cleanupEntries(files, secure, maxAge, maxBytes);
        }
    }

    private static Result cleanupEntries(DirectoryStream<Path> files, SecureDirectoryStream<Path> secure,
                                         Duration maxAge, long maxBytes) throws IOException {
        Instant now = Instant.now();
        Instant cutoff = now.minus(maxAge);
        Instant temporaryCutoff = now.minus(Duration.ofHours(6));
        List<Candidate> candidates = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            boolean temporary = TEMPORARY.matcher(name).matches();
            if (!temporary && !THUMBNAIL.matcher(name).matches()) continue;
            BasicFileAttributes attributes = regularFile(file, secure);
            if (attributes != null) candidates.add(new Candidate(file, attributes, temporary));
        }
        candidates.sort(Comparator.comparing(
                (Candidate candidate) -> candidate.attributes().lastModifiedTime()).reversed());

        int retained = 0, removed = 0;
        long retainedBytes = 0, removedBytes = 0;
        for (Candidate candidate : candidates) {
            BasicFileAttributes attributes = candidate.attributes();
            long size = attributes.size();
            Instant modified = attributes.lastModifiedTime().toInstant();
            boolean expired = candidate.temporary() ? modified.isBefore(temporaryCutoff)
                    : modified.isBefore(cutoff) || size > maxBytes - retainedBytes;
            if (expired) {
                if (deleteIfUnchanged(candidate, secure)) {
                    removed++;
                    removedBytes += size;
                }
            } else if (!candidate.temporary()) {
                retained++;
                retainedBytes += size;
            }
        }
        return new Result(retained, removed, retainedBytes, removedBytes);
    }

    private static BasicFileAttributes regularFile(Path file, SecureDirectoryStream<Path> secure) throws IOException {
        try {
            var attributes = secure == null
                    ? Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    : secure.getFileAttributeView(file.getFileName(), BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
            return attributes.isRegularFile() ? attributes : null;
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private static boolean deleteIfUnchanged(Candidate candidate, SecureDirectoryStream<Path> secure)
            throws IOException {
        Path file = candidate.path();
        try {
            var current = regularFile(file, secure);
            var original = candidate.attributes();
            if (current == null || current.size() != original.size()
                    || !current.lastModifiedTime().equals(original.lastModifiedTime())
                    || !Objects.equals(current.fileKey(), original.fileKey())) return false;
            if (secure != null) {
                secure.deleteFile(file.getFileName());
                return true;
            }
            // Providers without directory handles can only recheck immediately before a path-based delete.
            return file.equals(file.toRealPath()) && Files.deleteIfExists(file);
        } catch (NoSuchFileException ignored) {
            return false;
        }
    }
}
