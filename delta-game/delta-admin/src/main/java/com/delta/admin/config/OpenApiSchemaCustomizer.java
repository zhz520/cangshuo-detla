package com.delta.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps write-only credentials out of the published OpenAPI document.
 * Runtime serialization is already protected by Jackson; this closes the
 * contract/schema exposure as well.
 */
@Configuration
public class OpenApiSchemaCustomizer {

    @Bean
    public OpenApiCustomizer hideWriteOnlyCredentials() {
        return openApi -> {
            markWriteOnly(openApi, "Admin", "password");
            markWriteOnly(openApi, "SettingsRequest", "accessKeySecret");
        };
    }

    private void markWriteOnly(OpenAPI openApi, String schemaName, String property) {
        Schema<?> schema = schema(openApi, schemaName);
        if (schema != null && schema.getProperties() != null) {
            Schema<?> value = (Schema<?>) schema.getProperties().get(property);
            if (value != null) {
                value.setWriteOnly(true);
                value.setFormat("password");
            }
        }
    }

    private Schema<?> schema(OpenAPI openApi, String schemaName) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return null;
        return openApi.getComponents().getSchemas().get(schemaName);
    }

    private void hideProperty(OpenAPI openApi, String schemaName, String property) {
        Schema<?> schema = schema(openApi, schemaName);
        if (schema != null && schema.getProperties() != null) {
            schema.getProperties().remove(property);
        }
    }
}


