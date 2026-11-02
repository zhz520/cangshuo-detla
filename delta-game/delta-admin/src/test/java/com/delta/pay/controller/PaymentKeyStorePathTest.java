package com.delta.pay.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentKeyStorePathTest {
    @Test
    void defaultPaymentCertificatesLiveUnderPersistentUploads() {
        assertThat(PayController.resolveKeyStoreDirectory("", "/app/uploads/"))
                .isEqualTo(Path.of("/app/uploads/private/wxpay").toAbsolutePath().normalize());
    }

    @Test
    void explicitLegacyCertificateDirectoryStillWins() {
        assertThat(PayController.resolveKeyStoreDirectory(" /www/data/private/wxpay ", "/app/uploads/"))
                .isEqualTo(Path.of("/www/data/private/wxpay").toAbsolutePath().normalize());
    }
}
