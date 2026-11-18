package com.delta.admin.controller;

import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.IdentityBillingClient;
import com.delta.admin.service.IdentityAttemptLimitService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.file.service.ImageEncoding;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** User identity verification submission and administrator review. */
@RestController
@RequiredArgsConstructor
public class IdentityVerificationController {
    private static final String FEATURE = "identity_verification";
    private static final String PRIVACY_VERSION = "2026-11-02";
    private static final Set<String> STATUSES = Set.of("NOT_SUBMITTED", "DRAFT", "PENDING", "APPROVED", "REJECTED");
    private static final Semaphore IMAGE_DECODE_SLOTS = new Semaphore(2, true);
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private final JdbcTemplate jdbc;
    private final PlatformService platform;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.delta.common.security.service.RateLimitService limits;
    private final com.delta.system.service.SysConfigService configs;
    private final IdentityBillingClient identityBilling;
    private final IdentityAttemptLimitService attemptLimits;

    @Value("${identity.hash-pepper}")
    private String hashPepper;
    @Value("${identity.encryption-key}")
    private String encryptionKey;

    public record StatusView(boolean available, String reason, String status, String realName,
                             String idNumberMasked, String reviewNote, String submittedAt, String reviewedAt,
                             boolean hasFrontImage, boolean hasBackImage,
                             boolean autoAvailable, String autoReason) {}
    public record SubmitRequest(@NotBlank @Size(max = 64) String realName,
                                @NotBlank @Pattern(regexp = "[1-9][0-9]{16}[0-9Xx]", message = "请输入有效的18位身份证号码") String idNumber,
                                boolean privacyAccepted) {}
    public record ReviewRequest(boolean approved, @Size(max = 500) String note) {}
    public record AutoVerifyView(boolean matched, String status, String message) {}
    public record LimitRequest(Boolean enabled, Integer maxAttempts, Integer windowHours, Integer lockHours) {}
    private record EncodedImage(String base64, String mimeType) {}
    private record FeatureAccess(boolean available, String reason) {}

    @GetMapping("/user/identity/status")
    public R<StatusView> status(HttpServletRequest request) {
        FeatureAccess access = access(request);
        if (!access.available()) return R.ok(emptyStatus(access.reason()));
        Long userId = SecurityUtils.getUserId();
        var rows = jdbc.queryForList("SELECT real_name,id_number_masked,status,review_note,submitted_at,reviewed_at,front_image IS NOT NULL AS has_front,back_image IS NOT NULL AS has_back FROM identity_verification WHERE user_id=?", userId);
        if (rows.isEmpty()) return R.ok(emptyStatus(""));
        var row = rows.get(0);
        return R.ok(new StatusView(true, "", Objects.toString(value(row, "status"), "NOT_SUBMITTED"),
                Objects.toString(value(row, "real_name"), ""), Objects.toString(value(row, "id_number_masked"), ""),
                Objects.toString(value(row, "review_note"), ""), time(value(row, "submitted_at")), time(value(row, "reviewed_at")),
                number(value(row, "has_front")) > 0, number(value(row, "has_back")) > 0,
                autoAvailable(), autoReason()));
    }

    @GetMapping("/admin/identity-service/limits")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<IdentityAttemptLimitService.Settings> limits(HttpServletRequest request) {
        requireAccess(request);
        return R.ok(attemptLimits.settings());
    }

    @PutMapping("/admin/identity-service/limits")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "identity", operation = "修改实名失败次数限制")
    public R<IdentityAttemptLimitService.Settings> saveLimits(@RequestBody LimitRequest body, HttpServletRequest request) {
        requireAccess(request);
        if (body.enabled() == null || body.maxAttempts() == null || body.windowHours() == null || body.lockHours() == null)
            throw new BusinessException(400, "请完整填写实名失败次数限制");
        return R.ok(attemptLimits.save(body.enabled(), body.maxAttempts(), body.windowHours(), body.lockHours()));
    }

    @PostMapping("/user/identity/image")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam String side,
                                         HttpServletRequest request) throws Exception {
        requireAccess(request);
        if (!Set.of("front", "back").contains(side)) throw new BusinessException(400, "证件照片类型不正确");
        Long userId = SecurityUtils.getUserId();
        limits.check("identity-upload:" + userId, 12, 60);
        var current = jdbc.queryForList("SELECT status FROM identity_verification WHERE user_id=? FOR UPDATE", userId);
        if (!current.isEmpty() && Set.of("PENDING", "APPROVED").contains(value(current.get(0), "status")))
            throw new BusinessException(409, "当前实名资料正在审核或已通过，不能重新上传");
        EncodedImage image = encode(file);
        if ("front".equals(side)) {
            jdbc.update("INSERT INTO identity_verification(user_id,status,front_image,front_mime) VALUES (?,'DRAFT',?,?) ON DUPLICATE KEY UPDATE front_image=VALUES(front_image),front_mime=VALUES(front_mime),status=CASE WHEN status='REJECTED' THEN 'DRAFT' ELSE status END,updated_at=CURRENT_TIMESTAMP",
                    userId, image.base64(), image.mimeType());
        } else {
            jdbc.update("INSERT INTO identity_verification(user_id,status,back_image,back_mime) VALUES (?,'DRAFT',?,?) ON DUPLICATE KEY UPDATE back_image=VALUES(back_image),back_mime=VALUES(back_mime),status=CASE WHEN status='REJECTED' THEN 'DRAFT' ELSE status END,updated_at=CURRENT_TIMESTAMP",
                    userId, image.base64(), image.mimeType());
        }
        return R.ok(Map.of("side", side, "uploaded", true));
    }

    @PostMapping("/user/identity/submit")
    @Transactional(rollbackFor = Exception.class)
    @OpLog(module = "identity", operation = "提交实名认证资料")
    public R<Void> submit(@Valid @RequestBody SubmitRequest body, HttpServletRequest request) {
        requireAccess(request);
        if (!body.privacyAccepted()) throw new BusinessException(400, "请先阅读并同意实名认证隐私说明");
        String realName = body.realName().trim();
        if (!realName.matches("[\\p{IsHan}A-Za-z·. ]{2,32}")) throw new BusinessException(400, "真实姓名格式不正确");
        String idNumber = validateIdNumber(body.idNumber());
        Long userId = SecurityUtils.getUserId();
        var rows = jdbc.queryForList("SELECT status,front_image,back_image FROM identity_verification WHERE user_id=? FOR UPDATE", userId);
        if (rows.isEmpty()) throw new BusinessException(409, "请先上传证件照片");
        var row = rows.get(0);
        String currentStatus = Objects.toString(value(row, "status"), "DRAFT");
        if ("PENDING".equals(currentStatus)) throw new BusinessException(409, "实名资料正在审核，请勿重复提交");
        if ("APPROVED".equals(currentStatus)) throw new BusinessException(409, "实名认证已通过，无需重复提交");
        if (value(row, "front_image") == null || value(row, "back_image") == null) throw new BusinessException(409, "请完整上传身份证人像面和国徽面");
        String idHash = hash(idNumber);
        var duplicate = jdbc.queryForList("SELECT user_id FROM identity_verification WHERE id_number_hash=? AND user_id<>? LIMIT 1", idHash, userId);
        if (!duplicate.isEmpty()) throw new BusinessException(409, "该证件号码已绑定其他账号，请联系管理员");
        try {
            jdbc.update("UPDATE identity_verification SET real_name=?,id_number_hash=?,id_number_masked=?,privacy_version=?,privacy_accepted_at=CURRENT_TIMESTAMP,status='PENDING',review_note='',reviewer_id=NULL,submitted_at=CURRENT_TIMESTAMP,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                    realName, idHash, maskId(idNumber), PRIVACY_VERSION, userId);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(409, "该证件号码已绑定其他账号，请联系管理员");
        }
        return R.ok();
    }

    @PostMapping("/user/identity/auto-verify")
    @Transactional(rollbackFor = Exception.class)
    @OpLog(module = "identity", operation = "提交自动二要素实名核验")
    public R<AutoVerifyView> autoVerify(@Valid @RequestBody SubmitRequest body, HttpServletRequest request) {
        requireAccess(request);
        if (!autoEnabled()) throw new BusinessException(403, "自动实名核验尚未启用");
        if (!identityBilling.configured()) throw new BusinessException(503, "实名核验计费通道尚未配置");
        if (!body.privacyAccepted()) throw new BusinessException(400, "请先阅读并同意实名认证隐私说明");
        String realName = body.realName().trim();
        if (!realName.matches("[\\p{IsHan}A-Za-z·. ]{2,32}")) throw new BusinessException(400, "真实姓名格式不正确");
        String idNumber = validateIdNumber(body.idNumber());
        Long userId = SecurityUtils.getUserId();
        limits.check("identity-auto:" + userId, 10, 3600);
        attemptLimits.ensureAllowed(userId);
        String idHash = hash(idNumber);
        var duplicate = jdbc.queryForList("SELECT user_id FROM identity_verification WHERE id_number_hash=? AND user_id<>? LIMIT 1", idHash, userId);
        if (!duplicate.isEmpty()) throw new BusinessException(409, "该证件号码已绑定其他账号，请联系管理员");
        // Do not hold a row lock while the authority call runs: a mismatch is
        // recorded in a separate transaction so the failure counter survives
        // the 422 response.
        var existing = jdbc.queryForList("SELECT status FROM identity_verification WHERE user_id=?", userId);
        if (!existing.isEmpty() && Set.of("PENDING", "APPROVED").contains(String.valueOf(value(existing.get(0), "status"))))
            throw new BusinessException(409, "实名资料正在审核或已通过，请勿重复提交");

        // Authority idempotency keys are limited to 64 characters; keep a stable
        // user+identity digest so retries cannot be charged twice.
        String requestKey = "identity-" + hash(userId + ":" + realName + ":" + idNumber).substring(0, 48);
        IdentityBillingClient.Result result = identityBilling.verify(realName, idNumber, requestKey);
        if (!result.matched()) {
            attemptLimits.recordFailure(userId);
            throw new BusinessException(422, "姓名与证件号码不一致，请核对后重试");
        }
        attemptLimits.reset(userId);

        try {
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO identity_verification(user_id,real_name,id_number_hash,id_number_masked,status,privacy_version,privacy_accepted_at,review_note,submitted_at,reviewed_at) VALUES(?,?,?,?,'APPROVED',?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        userId, realName, idHash, maskId(idNumber), PRIVACY_VERSION, "授权中心二要素核验通过");
            } else {
                jdbc.update("UPDATE identity_verification SET real_name=?,id_number_hash=?,id_number_masked=?,status='APPROVED',privacy_version=?,privacy_accepted_at=CURRENT_TIMESTAMP,review_note=?,reviewer_id=NULL,submitted_at=CURRENT_TIMESTAMP,reviewed_at=CURRENT_TIMESTAMP,front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                        realName, idHash, maskId(idNumber), PRIVACY_VERSION, "授权中心二要素核验通过", userId);
            }
        } catch (DuplicateKeyException error) {
            throw new BusinessException(409, "该证件号码已绑定其他账号，请联系管理员");
        }
        events.publishEvent(new com.delta.common.event.BusinessEvent(this, "IDENTITY_REVIEWED", "USER", userId, null,
                "实名认证已通过。"));
        return R.ok(new AutoVerifyView(true, "APPROVED", "实名认证已通过"));
    }

    @GetMapping("/admin/identity/applications")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long taskId, @RequestParam(required = false) String status, HttpServletRequest request) {
        requireAccess(request);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !STATUSES.contains(normalized)) throw new BusinessException(400, "审核状态不正确");
        return R.ok(jdbc.queryForList("SELECT iv.user_id,iv.real_name,iv.id_number_masked,iv.status,iv.review_note,iv.submitted_at,iv.reviewed_at,u.nickname,u.phone FROM identity_verification iv JOIN `user` u ON u.id=iv.user_id WHERE iv.status<>'DRAFT' AND (?='' OR iv.status=?) AND (? IS NULL OR iv.user_id=?) ORDER BY CASE WHEN iv.status='PENDING' THEN 0 ELSE 1 END,iv.submitted_at DESC LIMIT 500", normalized, normalized, taskId, taskId)
                .stream().map(this::lowerKeys).toList());
    }

    @GetMapping("/admin/identity/applications/{userId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    @OpLog(module = "identity", operation = "查看实名认证资料")
    public ResponseEntity<R<Map<String, Object>>> detail(@PathVariable Long userId, HttpServletRequest request) {
        requireAccess(request);
        var rows = jdbc.queryForList("SELECT iv.*,u.nickname,u.phone FROM identity_verification iv JOIN `user` u ON u.id=iv.user_id WHERE iv.user_id=?", userId);
        if (rows.isEmpty()) throw new BusinessException(404, "实名认证记录不存在");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(lowerKeys(rows.get(0)));
        result.put("frontImage", dataUrl(result.remove("front_image"), result.get("front_mime")));
        result.put("backImage", dataUrl(result.remove("back_image"), result.get("back_mime")));
        result.remove("id_number_hash");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header("Pragma", "no-cache").body(R.ok(result));
    }

    @PutMapping("/admin/identity/applications/{userId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    @OpLog(module = "identity", operation = "人工审核实名认证")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> review(@PathVariable Long userId, @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        requireAccess(request);
        if (!body.approved() && (body.note() == null || body.note().isBlank())) throw new BusinessException(400, "驳回时必须填写原因");
        var rows = jdbc.queryForList("SELECT status FROM identity_verification WHERE user_id=? FOR UPDATE", userId);
        if (rows.isEmpty() || !"PENDING".equals(value(rows.get(0), "status"))) throw new BusinessException(409, "申请不存在或已处理");
        jdbc.update("UPDATE identity_verification SET status=?,review_note=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP,front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                body.approved() ? "APPROVED" : "REJECTED", Objects.toString(body.note(), ""), SecurityUtils.getUserId(), userId);
        events.publishEvent(new com.delta.common.event.BusinessEvent(this, "IDENTITY_REVIEWED", "USER", userId, null,
                body.approved() ? "实名认证已通过。" : "实名认证未通过，请在实名认证页面查看原因。"));
        return R.ok();
    }

    private FeatureAccess access(HttpServletRequest request) {
        try {
            verifyRequest(request);
            return new FeatureAccess(true, "");
        } catch (BusinessException error) {
            return new FeatureAccess(false, error.getMessage());
        }
    }

    private void requireAccess(HttpServletRequest request) {
        verifyRequest(request);
    }

    private void verifyRequest(HttpServletRequest request) {
        platform.requireFeature(FEATURE, request.getServerName());
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return;
        try {
            var uri = URI.create(origin);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            platform.requireFeature(FEATURE, uri.getHost());
        } catch (IllegalArgumentException error) {
            throw new BusinessException(403, "请求来源未获得实名认证模块授权");
        }
    }

    private StatusView emptyStatus(String reason) {
        boolean auto = reason == null || reason.isBlank() ? autoAvailable() : false;
        return new StatusView(reason == null || reason.isBlank(), reason == null ? "" : reason,
                reason == null || reason.isBlank() ? "NOT_SUBMITTED" : "UNAVAILABLE", "", "", "", null, null, false, false,
                auto, auto ? "" : autoReason());
    }

    private boolean autoEnabled() {
        return "true".equalsIgnoreCase(configs.getConfigValue("identity.auto.enabled", "false"));
    }

    private boolean autoAvailable() {
        return autoEnabled() && identityBilling.configured();
    }

    private String autoReason() {
        if (!autoEnabled()) return "当前使用人工证件审核";
        return identityBilling.configured() ? "" : "自动实名核验通道尚未配置";
    }

    private EncodedImage encode(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择证件照片");
        if (file.getSize() > 8L * 1024 * 1024) throw new BusinessException(400, "证件照片不能超过8MB");
        if (!IMAGE_DECODE_SLOTS.tryAcquire()) throw new BusinessException(429, "证件照片正在处理中，请稍后重试");
        try {
            BufferedImage source;
            try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
                if (input == null) throw new BusinessException(400, "无法识别证件照片");
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new BusinessException(400, "无法识别证件照片");
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "jpg", "png").contains(format)) throw new BusinessException(400, "证件照片仅支持JPG或PNG");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 160 || height < 100) throw new BusinessException(400, "证件照片尺寸过小");
                    if (width > 4000 || height > 4000 || (long) width * height > 10_000_000L) throw new BusinessException(400, "证件照片分辨率过大");
                    source = reader.read(0);
                } finally { reader.dispose(); }
            }
            if (source == null) throw new BusinessException(400, "无法识别证件照片");
            BufferedImage image = ImageEncoding.resize(source, 1600, 1600);
            try (var output = new ByteArrayOutputStream()) {
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try { graphics.drawImage(image, 0, 0, null); } finally { graphics.dispose(); }
                if (!ImageIO.write(rgb, "jpg", output)) throw new BusinessException(400, "证件照片处理失败");
                return new EncodedImage(encrypt(output.toByteArray()), "image/jpeg");
            }
        } finally {
            IMAGE_DECODE_SLOTS.release();
        }
    }

    private String validateIdNumber(String value) {
        String id = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!id.matches("[1-9][0-9]{16}[0-9X]")) throw new BusinessException(400, "请输入有效的18位身份证号码");
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (id.charAt(i) - '0') * ID_WEIGHTS[i];
        if (id.charAt(17) != ID_CHECK_CODES[sum % 11]) throw new BusinessException(400, "身份证号码校验失败");
        return id;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((hashPepper + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String maskId(String id) { return id.substring(0, 3) + "***********" + id.substring(14); }
    private Object value(Map<String, Object> row, String key) {
        for (var entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        return null;
    }
    private Map<String, Object> lowerKeys(Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }
    private long number(Object value) {
        if (value instanceof Number valueNumber) return valueNumber.longValue();
        if (value instanceof Boolean valueBoolean) return valueBoolean ? 1L : 0L;
        return 0L;
    }
    private String time(Object value) { return value == null ? "" : value.toString(); }
    private String dataUrl(Object data, Object mime) {
        return data == null || mime == null ? null : "data:" + mime + ";base64," + decrypt(String.valueOf(data));
    }
    private SecretKeySpec encryptionSecret() throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)), "AES");
    }
    private String encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionSecret(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain), packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length); System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(packed);
    }
    private String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded), iv = Arrays.copyOfRange(packed, 0, 12);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionSecret(), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)));
        } catch (Exception error) {
            throw new IllegalStateException("证件照片解密失败", error);
        }
    }
}
