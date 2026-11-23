package com.delta.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts per-tenant provider credentials before they are persisted.
 * The key is derived from the existing identity encryption key so operators
 * do not need to provision another independent secret for notification data.
 */
@Component
public class SecretCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(@Value("${identity.encryption-key}") String rawKey) {
        if (rawKey == null || rawKey.isBlank()) throw new IllegalArgumentException("identity.encryption-key is required");
        try {
            this.key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize credential encryption", error);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt credential", error);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        if (!encoded.startsWith(PREFIX)) throw new IllegalArgumentException("Unsupported encrypted credential format");
        try {
            byte[] packed = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("Invalid encrypted credential");
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, IV_BYTES, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to decrypt credential", error);
        }
    }
}
