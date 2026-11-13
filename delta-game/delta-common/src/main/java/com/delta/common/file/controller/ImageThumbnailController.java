package com.delta.common.file.controller;

import com.delta.common.file.service.ImageEncoding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantIds;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Derivatives of already-public upload images. Never fetch remote URLs or arbitrary paths. */
@RestController
public class ImageThumbnailController {
    @Value("${file.upload.path:/data/upload}")
    private String uploadPath;
    @Autowired(required = false) private TenantProperties tenantProperties;
    @Autowired(required = false) private TenantRegistry tenantRegistry;
    private final Semaphore workers = new Semaphore(2);

    @GetMapping("/file/thumbnail/{width}/{year}/{month}/{day}/{filename:.+}")
    public ResponseEntity<FileSystemResource> thumbnail(@PathVariable int width, @PathVariable String year,
            @PathVariable String month, @PathVariable String day, @PathVariable String filename) throws Exception {
        if(!sharedMode()) return thumbnailFor(null,width,year,month,day,filename);
        String current = TenantContext.currentIdOrNull();
        if(current==null || tenantRegistry==null || tenantRegistry.findById(current).filter(t->t.active()).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return thumbnailFor(current,width,year,month,day,filename);
    }

    @GetMapping("/file/thumbnail/{width}/{tenant}/{year}/{month}/{day}/{filename:.+}")
    public ResponseEntity<FileSystemResource> tenantThumbnail(@PathVariable int width,@PathVariable String tenant,
            @PathVariable String year,@PathVariable String month,@PathVariable String day,@PathVariable String filename) throws Exception {
        if(!sharedMode())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String id=TenantIds.requireContext(tenant);
        String current = TenantContext.currentIdOrNull();
        if(current==null || !current.equals(id) || tenantRegistry==null || tenantRegistry.findById(id).filter(t->t.active()).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return thumbnailFor(id,width,year,month,day,filename);
    }

    private ResponseEntity<FileSystemResource> thumbnailFor(String tenantId,int width,String year,String month,String day,String filename) throws Exception {
        String relative = year + "/" + month + "/" + day + "/" + filename;
        if (!Set.of(160, 320, 640, 1080).contains(width)
                || !relative.matches("\\d{4}/\\d{2}/\\d{2}/[a-fA-F0-9-]{32,36}\\.(?i:png|jpg|jpeg|gif)"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path rootPath = tenantId==null?Paths.get(uploadPath):Paths.get(uploadPath,tenantId);
        if (!Files.isDirectory(rootPath)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path root = rootPath.toRealPath();
        Path source = root.resolve(relative).normalize();
        if (!Files.isRegularFile(source) || !source.toRealPath().startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (relative + ":" + width + ":" + Files.size(source) + ":" + Files.getLastModifiedTime(source).toMillis()).getBytes(StandardCharsets.UTF_8)));
        Path cache = root.resolve(".thumbnails");
        Files.createDirectories(cache);
        if (!cache.toRealPath().startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path jpg = cache.resolve(digest + ".jpg"), png = cache.resolve(digest + ".png");
        Path result = Files.isRegularFile(jpg) ? jpg : Files.isRegularFile(png) ? png : null;
        if (result == null) {
            if (!workers.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            Path temp = null;
            try (var input = ImageIO.createImageInputStream(source.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 1 || h < 1 || (long) w * h > 16_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    var image = ImageEncoding.resize(reader.read(0), width, width * 4);
                    boolean transparent = ImageEncoding.hasTransparency(image);
                    result = transparent ? png : jpg;
                    temp = Files.createTempFile(cache, "encoding-", ".tmp");
                    ImageEncoding.write(image, temp, transparent);
                    Files.move(temp, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally { reader.dispose(); }
            } finally {
                if (temp != null) Files.deleteIfExists(temp);
                workers.release();
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic()
                        .immutable().staleWhileRevalidate(Duration.ofDays(7)))
                .eTag(digest).contentType(result.equals(png) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(result));
    }

    private boolean sharedMode(){return tenantProperties!=null&&tenantProperties.isEnabled();}
}
