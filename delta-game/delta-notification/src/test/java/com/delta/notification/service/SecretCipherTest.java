package com.delta.notification.service;

import com.delta.common.security.SecretCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {
    private final SecretCipher cipher = new SecretCipher("test-only-wechat-encryption-key-32-bytes");

    @Test
    void roundTripsSensitiveValuesWithoutEmbeddingPlaintext() {
        String encrypted = cipher.encrypt("wx-official-app-secret");

        assertThat(encrypted).startsWith("v1:").doesNotContain("wx-official-app-secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("wx-official-app-secret");
    }

    @Test
    void blankValuesStayBlank() {
        assertThat(cipher.encrypt(" ")).isEmpty();
        assertThat(cipher.decrypt("")).isEmpty();
    }
}
