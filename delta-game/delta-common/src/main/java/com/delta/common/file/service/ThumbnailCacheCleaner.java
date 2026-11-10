package com.delta.common.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounds derivative files created by ImageThumbnailController. */
@Slf4j
@Component
public class ThumbnailCacheCleaner {
    private static final Pattern THUMBNAIL = Pattern.compile("[a-f0-9]{64}\\.(?:jpg|png)");
    private static final Pattern TEMPORARY = Pattern.compile("encoding-[^/]+" + Pattern.quote(".tmp"));

    @Value("${file.upload.path:/data/upload}")
    private String uploadPath;
    @Value("${file.thumbnail.retention-days:45}")
    private long retentionDays;
    @Value("${file.thumbnail.max-size-mb:2048}")
    private long maxSizeMb;
    @Autowired(required=false) private TenantProperties tenantProperties;
    @Autowired(required=false) private TenantRegistry tenantRegistry;

    public record Result(int retained, int removed, long retainedBytes, long removedBytes) {}

    @Scheduled(cron = "${file.thumbnail.cleanup-cron:0 20 4 * * *}")
    public void scheduledCleanup() {
        try {
            long maxBytes=Math.max(1,maxSizeMb)*1024*1024;
            Duration age=Duration.ofDays(Math.max(1,retentionDays));
            if(tenantProperties!=null&&tenantProperties.isEnabled()){
                for(var tenant:tenantRegistry.activeTenants()){
                    Result result=cleanup(Paths.get(uploadPath,tenant.id()),age,maxBytes);
                    if(result.removed()>0)log.info("租户[{}]缩略图缓存清理完成: retained={}, removed={}, freedBytes={}",
                            tenant.id(),result.retained(),result.removed(),result.removedBytes());
                }
            }else{
                Result result = cleanup(Paths.get(uploadPath),age,maxBytes);
                if (result.removed() > 0) log.info("缩略图缓存清理完成: retained={}, removed={}, freedBytes={}",
                        result.retained(), result.removed(), result.removedBytes());
            }
        } catch (Exception error) {
            log.warn("缩略图缓存清理失败: {}", error.getClass().getSimpleName());
        }
    }

    public static Result cleanup(Path uploadRoot, Duration maxAge, long maxBytes) throws IOException {
        if (uploadRoot == null || !Files.isDirectory(uploadRoot)) return new Result(0, 0, 0, 0);
        Path root = uploadRoot.toRealPath();
        Path cache = root.resolve(".thumbnails");
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) return new Result(0, 0, 0, 0);
        Path realCache = cache.toRealPath();
        if (!realCache.startsWith(root) || !root.equals(realCache.getParent()))
            throw new IOException("thumbnail cache escaped upload root");

        Instant cutoff = Instant.now().minus(maxAge);
        Instant temporaryCutoff = Instant.now().minus(Duration.ofHours(6));
        List<Path> files;
        try (var stream = Files.list(realCache)) { files = new ArrayList<>(stream.toList()); }
        files.sort(Comparator.comparing(ThumbnailCacheCleaner::modified, Comparator.reverseOrder()));

        int retained = 0, removed = 0;
        long retainedBytes = 0, removedBytes = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            boolean derivative = THUMBNAIL.matcher(name).matches();
            boolean temporary = TEMPORARY.matcher(name).matches();
            if (!derivative && !temporary) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            long size = Files.size(file);
            FileTime modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
            boolean expired = temporary ? modified.toInstant().isBefore(temporaryCutoff)
                    : modified.toInstant().isBefore(cutoff) || retainedBytes + size > maxBytes;
            if (expired) {
                if (Files.deleteIfExists(file)) { removed++; removedBytes += size; }
            } else if (derivative) {
                retained++; retainedBytes += size;
            }
        }
        return new Result(retained, removed, retainedBytes, removedBytes);
    }

    private static Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(); }
        catch (IOException ignored) { return Instant.EPOCH; }
    }
}
