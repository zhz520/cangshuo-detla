package com.delta.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fails closed when a production instance is still using development credentials. */
@Configuration
@Profile("prod")
public class ProductionSecurityValidator {
    @Bean
    ApplicationRunner validateProductionSecrets(
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${app.allowed-origins:}") String allowedOrigins,
            @Value("${identity.hash-pepper:}") String identityPepper,
            @Value("${identity.encryption-key:}") String identityEncryptionKey) {
        return (ApplicationArguments arguments) -> {
            if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32
                    || jwtSecret.contains("change_me") || jwtSecret.startsWith("REPLACE_"))
                throw new IllegalStateException("生产环境 JWT_SECRET 必须是至少 32 字节的随机值");
            if (databasePassword == null || databasePassword.isBlank() || databasePassword.equals("change_me")
                    || databasePassword.startsWith("REPLACE_"))
                throw new IllegalStateException("生产环境数据库密码未配置或仍为默认值");
            if (redisPassword == null || redisPassword.isBlank() || redisPassword.startsWith("REPLACE_"))
                throw new IllegalStateException("生产环境 Redis 密码未配置");
            if (identityPepper == null || identityPepper.getBytes(StandardCharsets.UTF_8).length < 32
                    || identityPepper.startsWith("REPLACE_"))
                throw new IllegalStateException("生产环境实名散列盐必须是至少 32 字节的随机值");
            if (identityEncryptionKey == null || identityEncryptionKey.getBytes(StandardCharsets.UTF_8).length < 32
                    || identityEncryptionKey.startsWith("REPLACE_") || identityEncryptionKey.equals(identityPepper))
                throw new IllegalStateException("生产环境实名加密密钥未配置或与散列盐重复");
            if (allowedOrigins == null || Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                    .anyMatch(origin -> origin.isEmpty() || origin.equals("*") || !origin.startsWith("https://")))
                throw new IllegalStateException("生产环境 ALLOWED_ORIGINS 必须只包含明确的 HTTPS 来源");
        };
    }
}
