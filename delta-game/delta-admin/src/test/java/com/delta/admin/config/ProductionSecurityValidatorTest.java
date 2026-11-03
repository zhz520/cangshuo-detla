package com.delta.admin.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityValidatorTest {
    private final ProductionSecurityValidator validator = new ProductionSecurityValidator();

    @Test
    void acceptsExplicitProductionSecrets() {
        var runner = validator.validateProductionSecrets(
                "jwt-secret-that-is-longer-than-thirty-two-bytes",
                "database-password",
                "redis-password",
                "https://jgs.kk520yy.com,https://jgsapi.kk520yy.com",
                "identity-hash-pepper-that-is-longer-than-thirty-two-bytes",
                "identity-encryption-key-that-is-longer-than-thirty-two-bytes");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDefaultJwtSecret() {
        var runner = validator.validateProductionSecrets(
                "change_me_jwt_secret_at_least_256_bits",
                "database-password",
                "redis-password",
                "https://jgs.kk520yy.com",
                "identity-hash-pepper-that-is-longer-than-thirty-two-bytes",
                "identity-encryption-key-that-is-longer-than-thirty-two-bytes");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsWildcardOrInsecureOrigin() {
        var runner = validator.validateProductionSecrets(
                "jwt-secret-that-is-longer-than-thirty-two-bytes",
                "database-password",
                "redis-password",
                "http://103.38.82.146:7420,",
                "identity-hash-pepper-that-is-longer-than-thirty-two-bytes",
                "identity-encryption-key-that-is-longer-than-thirty-two-bytes");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALLOWED_ORIGINS");
    }
}
