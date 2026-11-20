package com.delta.admin.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exports the runtime OpenAPI document so contract changes are reviewed as data,
 * not only as source-level path scans.
 *
 * <p>Run with {@code -Dopenapi.baseline.write=true -Dopenapi.baseline.path=<abs path>}
 * to refresh {@code contracts/openapi.baseline.json}. The default test run only
 * verifies that the document can be generated and remains non-trivial.</p>
 */
@SpringBootTest(classes = OpenApiTestApplication.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "jwt.secret=openapi-baseline-test-secret-at-least-256-bits-long",
        "aes.key=0123456789abcdef",
        "identity.hash-pepper=openapi-baseline-test-pepper",
        "identity.encryption-key=openapi-baseline-test-key",
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/v3/api-docs",
        "springdoc.packages-to-scan=com.delta",
        "springdoc.paths-to-match=/**",
        "springdoc.swagger-ui.path=/doc.html",
        "platform.demo-readonly-enabled=true",
        "platform.state-path=target/openapi-platform-state.json",
        "platform.report-url=",
        "platform.report.initial-delay-ms=3600000",
        "file.upload.path=target/openapi-upload"
})
@AutoConfigureMockMvc
class OpenApiBaselineTest {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    void exportOpenApiDocument() throws Exception {
        var response = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String json = response.getContentAsString(StandardCharsets.UTF_8);
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var document = mapper.readTree(json);
        assertThat(document.path("openapi").asText())
                .as("OpenAPI document; body starts with: %s", json.substring(0, Math.min(160, json.length())))
                .startsWith("3.");
        assertThat(document.path("paths").size()).isGreaterThan(100);

        if (Boolean.getBoolean("openapi.baseline.write")) {
            String target = System.getProperty("openapi.baseline.path");
            assertThat(target).as("openapi.baseline.path").isNotBlank();
            Path path = Path.of(target).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, mapper.writeValueAsString(document) + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}


