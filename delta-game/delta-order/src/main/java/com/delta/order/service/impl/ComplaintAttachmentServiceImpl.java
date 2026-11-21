package com.delta.order.service.impl;

import com.delta.common.exception.BusinessException;
import com.delta.common.file.service.ImageEncoding;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantIds;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.utils.ImageListUtils;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.dto.ComplaintAttachmentView;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.service.ComplaintAttachmentService;
import com.delta.order.service.ComplaintAttachmentToken;
import com.delta.order.service.ComplaintAttachmentTokenCodec;
import com.delta.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class ComplaintAttachmentServiceImpl implements ComplaintAttachmentService {
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final int MAX_ATTACHMENTS = 9;
    private static final int MAX_API_ITEMS = 50;
    private static final Set<String> SOURCE_FORMATS = Set.of("jpeg", "jpg", "png", "gif");
    private static final Semaphore IMAGE_DECODE_SLOTS = new Semaphore(2, true);

    private final OrderService orderService;
    private final CrossModuleMapper crossModuleMapper;
    private final JdbcTemplate jdbc;
    private final ComplaintAttachmentTokenCodec tokenCodec;

    @Value("${file.upload.path:/data/upload}")
    private String uploadPath;
    @Autowired(required = false)
    private TenantProperties tenantProperties;

    @Override
    public String upload(Long orderId, Long actorId, String actorType, MultipartFile file) throws Exception {
        Order order = requireAccessibleOrder(orderId, actorId, actorType);
        String tenant = currentTenant();
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException(400, "证据图片须在10MB以内");
        }
        if (!IMAGE_DECODE_SLOTS.tryAcquire()) {
            throw new BusinessException(429, "图片处理繁忙，请稍后重试");
        }
        try {
            BufferedImage decoded = decode(file);
            Path root = privateRoot(tenant);
            boolean transparent = ImageEncoding.hasTransparency(decoded);
            String fileName = UUID.randomUUID().toString().replace("-", "") + (transparent ? ".png" : ".jpg");
            Path target = root.resolve(fileName).normalize();
            if (!Objects.equals(root, target.getParent())) {
                throw new BusinessException(500, "证据图片保存失败");
            }
            Path pending = Files.createTempFile(root, ".pending-", ".img");
            try {
                ImageEncoding.write(ImageEncoding.resize(decoded, 1920, 1920), pending, transparent);
                try {
                    Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(pending, target);
                }
            } finally {
                Files.deleteIfExists(pending);
            }
            return tokenCodec.encode(new ComplaintAttachmentToken(actorId, actorType, order.getId(),
                    tenant, fileName, System.currentTimeMillis()));
        } catch (BusinessException error) {
            throw error;
        } catch (IOException error) {
            throw new BusinessException(500, "证据图片保存失败");
        } finally {
            IMAGE_DECODE_SLOTS.release();
        }
    }

    @Override
    public String bindToComplaint(Long orderId, Long actorId, String actorType,
                                  List<String> attachmentIds, String legacyImages) {
        List<ComplaintAttachmentToken> tokens = requestedTokens(attachmentIds, legacyImages);
        for (ComplaintAttachmentToken token : tokens) {
            verifyOrderAndTenant(token, orderId);
            if (!Objects.equals(token.actorId(), actorId) || !Objects.equals(token.actorType(), actorType)) {
                throw new BusinessException(400, "投诉附件不属于当前用户");
            }
            requirePrivateFile(token);
        }
        return join(attachmentIds, legacyImages);
    }

    @Override
    public String bindToStaffCreatedComplaint(Long orderId, List<String> attachmentIds, String legacyImages) {
        Order order = orderService.getById(orderId);
        if (order == null) throw new BusinessException(404, "订单不存在");
        List<ComplaintAttachmentToken> tokens = requestedTokens(attachmentIds, legacyImages);
        for (ComplaintAttachmentToken token : tokens) {
            verifyOrderAndTenant(token, orderId);
            if (!tokenActorBelongsToOrder(token, order)) {
                throw new BusinessException(400, "投诉附件不属于该订单用户");
            }
            requirePrivateFile(token);
        }
        return join(attachmentIds, legacyImages);
    }

    @Override
    public List<ComplaintAttachmentView> views(String storedTokens, Long orderId) {
        String normalized = ImageListUtils.normalize(storedTokens);
        if (normalized == null) return List.of();
        List<ComplaintAttachmentView> result = new ArrayList<>();
        for (String raw : normalized.split(",")) {
            if (result.size() >= MAX_API_ITEMS) break;
            String value = raw.trim();
            if (value.isEmpty()) continue;
            Optional<ComplaintAttachmentToken> token = tokenCodec.decode(value);
            boolean usable = token.isPresent() && orderId != null && orderId.equals(token.get().orderId())
                    && tenantMatches(token.get()) && privateFileExists(token.get());
            result.add(usable
                    ? new ComplaintAttachmentView(value, ComplaintAttachmentView.KIND_IMAGE)
                    : new ComplaintAttachmentView(null, ComplaintAttachmentView.KIND_UNAVAILABLE));
        }
        return result;
    }

    @Override
    public Optional<ComplaintAttachmentContent> loadForOwner(String token, Long actorId, String actorType) {
        Optional<ComplaintAttachmentToken> decoded = tokenCodec.decode(token);
        if (decoded.isEmpty()) return Optional.empty();
        ComplaintAttachmentToken value = decoded.get();
        if (!Objects.equals(value.actorId(), actorId) || !Objects.equals(value.actorType(), actorType)
                || !tenantMatches(value)) {
            return Optional.empty();
        }
        Order order = orderService.getById(value.orderId());
        if (!canAccessOrder(order, actorId, actorType) || !boundToOrder(value.orderId(), token)) {
            return Optional.empty();
        }
        return read(value);
    }

    @Override
    public Optional<ComplaintAttachmentContent> loadForStaff(Complaint complaint, String token) {
        if (complaint == null || complaint.getOrderId() == null) return Optional.empty();
        Optional<ComplaintAttachmentToken> decoded = tokenCodec.decode(token);
        if (decoded.isEmpty()) return Optional.empty();
        ComplaintAttachmentToken value = decoded.get();
        if (!value.orderId().equals(complaint.getOrderId()) || !tenantMatches(value)) return Optional.empty();
        if (!contains(complaint.getImages(), token) && !contains(complaint.getAppealImages(), token)) {
            return Optional.empty();
        }
        return read(value);
    }

    private Order requireAccessibleOrder(Long orderId, Long actorId, String actorType) {
        Order order = orderId == null ? null : orderService.getById(orderId);
        if (!canAccessOrder(order, actorId, actorType)) throw new BusinessException(404, "订单不存在");
        return order;
    }

    private boolean canAccessOrder(Order order, Long actorId, String actorType) {
        if (order == null || order.getUserId() == null || actorId == null) return false;
        if (actorId.equals(order.getUserId())) return true;
        return "USER".equals(actorType) && crossModuleMapper.countGuestLink(actorId, order.getUserId()) > 0;
    }

    private boolean tokenActorBelongsToOrder(ComplaintAttachmentToken token, Order order) {
        if (Objects.equals(token.actorId(), order.getUserId())) return true;
        return "USER".equals(token.actorType())
                && crossModuleMapper.countGuestLink(token.actorId(), order.getUserId()) > 0;
    }

    private List<ComplaintAttachmentToken> requestedTokens(List<String> attachmentIds, String legacyImages) {
        String normalizedLegacy = ImageListUtils.normalize(legacyImages);
        List<String> raw = new ArrayList<>();
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            if (normalizedLegacy != null) {
                throw new BusinessException(400, "请勿同时提交 attachmentIds 和旧图片字段");
            }
            raw.addAll(attachmentIds);
        } else if (normalizedLegacy != null) {
            raw.addAll(Arrays.asList(normalizedLegacy.split(",")));
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            String value = item == null ? "" : item.trim();
            if (value.isEmpty() || value.length() > 2048) {
                throw new BusinessException(400, "投诉附件无效，请重新上传");
            }
            unique.add(value);
        }
        if (unique.size() > MAX_ATTACHMENTS) throw new BusinessException(400, "图片数量超过限制");
        List<ComplaintAttachmentToken> result = new ArrayList<>();
        for (String value : unique) {
            ComplaintAttachmentToken token = tokenCodec.decode(value)
                    .orElseThrow(() -> new BusinessException(400, "投诉附件无效，请重新上传"));
            result.add(token);
        }
        return result;
    }

    private String join(List<String> attachmentIds, String legacyImages) {
        List<String> raw = new ArrayList<>();
        if (attachmentIds != null && !attachmentIds.isEmpty()) raw.addAll(attachmentIds);
        else {
            String normalizedLegacy = ImageListUtils.normalize(legacyImages);
            if (normalizedLegacy != null) raw.addAll(Arrays.asList(normalizedLegacy.split(",")));
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) unique.add(item.trim());
        }
        return unique.isEmpty() ? null : String.join(",", unique);
    }

    private void verifyOrderAndTenant(ComplaintAttachmentToken token, Long orderId) {
        if (!Objects.equals(token.orderId(), orderId)) {
            throw new BusinessException(400, "投诉附件不属于该订单");
        }
        if (!tenantMatches(token)) {
            throw new BusinessException(400, "投诉附件不属于当前租户");
        }
    }

    private boolean tenantMatches(ComplaintAttachmentToken token) {
        return Objects.equals(token.tenantId(), currentTenant());
    }

    private String currentTenant() {
        String tenant = TenantContext.currentIdOrNull();
        if (tenant == null && tenantProperties != null && tenantProperties.isEnabled()) {
            throw new BusinessException(503, "租户上下文不可用，请联系维护人员");
        }
        return tenant;
    }

    private boolean boundToOrder(Long orderId, String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT images, appeal_images FROM complaint WHERE order_id=?", orderId);
        for (Map<String, Object> row : rows) {
            if (contains(asText(row.get("images")), token) || contains(asText(row.get("appeal_images")), token)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String stored, String token) {
        String normalized = ImageListUtils.normalize(stored);
        if (normalized == null) return false;
        for (String item : normalized.split(",")) {
            if (token.equals(item.trim())) return true;
        }
        return false;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BufferedImage decode(MultipartFile file) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            if (input == null) throw new BusinessException(400, "仅支持图片证据");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(400, "仅支持图片证据");
            var reader = readers.next();
            try {
                if (!SOURCE_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(400, "仅支持 JPG、PNG 或 GIF 图片");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException(400, "图片分辨率过大");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new BusinessException(400, "图片内容无效");
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }

    private Path privateRoot() throws IOException {
        return privateRoot(currentTenant());
    }

    private Path privateRoot(String tenant) throws IOException {
        Path base = Paths.get(uploadPath == null || uploadPath.isBlank() ? "./uploads" : uploadPath)
                .toAbsolutePath().normalize();
        Files.createDirectories(base);
        String segment = tenant == null ? "default" : TenantIds.requireValid(tenant);
        Path root = base.resolve(segment).resolve(".private").resolve("complaints");
        Files.createDirectories(root);
        Path realBase = base.toRealPath();
        Path realRoot = root.toRealPath();
        if (!realRoot.startsWith(realBase)) throw new BusinessException(500, "证据目录不可用");
        return realRoot;
    }

    private Path privateFile(ComplaintAttachmentToken token) {
        try {
            Path root = privateRoot();
            Path file = root.resolve(token.fileName()).normalize();
            if (!Objects.equals(root, file.getParent())) return null;
            return file;
        } catch (IOException | BusinessException error) {
            return null;
        }
    }

    private boolean privateFileExists(ComplaintAttachmentToken token) {
        Path file = privateFile(token);
        return file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private void requirePrivateFile(ComplaintAttachmentToken token) {
        if (!privateFileExists(token)) throw new BusinessException(400, "投诉附件不存在，请重新上传");
    }

    private Optional<ComplaintAttachmentContent> read(ComplaintAttachmentToken token) {
        if (!tenantMatches(token)) return Optional.empty();
        Path file = privateFile(token);
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (Files.size(file) > MAX_UPLOAD_BYTES) return Optional.empty();
            String contentType = token.fileName().endsWith(".png") ? "image/png" : "image/jpeg";
            return Optional.of(new ComplaintAttachmentContent(Files.readAllBytes(file), contentType));
        } catch (IOException error) {
            return Optional.empty();
        }
    }
}
