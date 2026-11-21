package com.delta.order.service;

import com.delta.common.tenant.TenantIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Encrypts attachment metadata with AES-GCM so a token is opaque while still
 * authenticating the actor, order, tenant and private file name.
 */
@Component
public class ComplaintAttachmentTokenCodec {
    private static final String PREFIX = "ca1.";
    private static final byte VERSION = 1;
    private static final byte ACTOR_USER = 1;
    private static final byte ACTOR_GUEST = 2;
    private static final byte[] AAD = "delta-complaint-attachment-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final Pattern FILE_NAME = Pattern.compile("[a-f0-9]{32}\\.(?:jpg|png)");
    private static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final Set<String> ACTOR_TYPES = Set.of("USER", "GUEST");
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ComplaintAttachmentTokenCodec(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 不可用，无法创建投诉附件密钥");
        }
        try {
            byte[] material = MessageDigest.getInstance("SHA-256")
                    .digest(("delta-complaint-attachment\u0000" + secret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("投诉附件密钥初始化失败", error);
        }
    }

    public String encode(ComplaintAttachmentToken token) {
        validate(token);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plain(token));
            ByteBuffer result = ByteBuffer.allocate(iv.length + encrypted.length);
            result.put(iv).put(encrypted);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(result.array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("投诉附件 token 生成失败", error);
        }
    }

    public Optional<ComplaintAttachmentToken> decode(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(token.substring(PREFIX.length()));
            if (packed.length <= IV_BYTES + 16) return Optional.empty();
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            System.arraycopy(packed, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return Optional.of(read(cipher.doFinal(encrypted)));
        } catch (GeneralSecurityException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] plain(ComplaintAttachmentToken token) {
        byte[] tenant = token.tenantId() == null ? new byte[0] : token.tenantId().getBytes(StandardCharsets.UTF_8);
        byte[] fileName = token.fileName().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 + tenant.length + fileName.length);
        output.write(VERSION);
        writeLong(output, token.issuedAt());
        writeLong(output, token.actorId());
        writeLong(output, token.orderId());
        output.write(actorCode(token.actorType()));
        output.write(tenant.length);
        output.writeBytes(tenant);
        output.write(fileName.length);
        output.writeBytes(fileName);
        return output.toByteArray();
    }

    private static ComplaintAttachmentToken read(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (input.get() != VERSION) throw new IllegalArgumentException("unsupported attachment token");
        long issuedAt = input.getLong();
        long actorId = input.getLong();
        long orderId = input.getLong();
        String actorType = actorType(input.get());
        int tenantLength = Byte.toUnsignedInt(input.get());
        byte[] tenantBytes = new byte[tenantLength];
        input.get(tenantBytes);
        int fileNameLength = Byte.toUnsignedInt(input.get());
        byte[] fileNameBytes = new byte[fileNameLength];
        input.get(fileNameBytes);
        if (input.hasRemaining()) throw new IllegalArgumentException("trailing attachment token data");
        String tenantId = tenantBytes.length == 0 ? null : new String(tenantBytes, StandardCharsets.UTF_8);
        ComplaintAttachmentToken token = new ComplaintAttachmentToken(actorId, actorType, orderId, tenantId,
                new String(fileNameBytes, StandardCharsets.US_ASCII), issuedAt);
        validate(token);
        return token;
    }

    private static void validate(ComplaintAttachmentToken token) {
        if (token == null || token.actorId() == null || token.actorId() <= 0
                || token.orderId() == null || token.orderId() <= 0 || token.issuedAt() <= 0
                || !ACTOR_TYPES.contains(token.actorType())
                || token.fileName() == null || !FILE_NAME.matcher(token.fileName()).matches()
                || (token.tenantId() != null && !TENANT_ID.matcher(token.tenantId()).matches())) {
            throw new IllegalArgumentException("invalid complaint attachment token");
        }
        if (token.tenantId() != null) TenantIds.requireValid(token.tenantId());
    }

    private static byte actorCode(String actorType) {
        return switch (actorType) {
            case "USER" -> ACTOR_USER;
            case "GUEST" -> ACTOR_GUEST;
            default -> throw new IllegalArgumentException("unsupported attachment actor");
        };
    }

    private static String actorType(byte code) {
        return switch (code) {
            case ACTOR_USER -> "USER";
            case ACTOR_GUEST -> "GUEST";
            default -> throw new IllegalArgumentException("unsupported attachment actor");
        };
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (value >>> shift));
    }
}
