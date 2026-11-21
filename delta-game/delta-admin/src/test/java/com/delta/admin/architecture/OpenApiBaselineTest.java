package com.delta.admin.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exports the runtime OpenAPI document so contract changes are reviewed as data,
 * not only as source-level path scans.
 *
 * <p>Run with {@code -Dopenapi.baseline.write=true -Dopenapi.baseline.path=<abs path>}
 * to explicitly export {@code contracts/openapi.baseline.json}. A normal test run
 * compares the runtime contract without writing the baseline.</p>
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

    private static final String BASELINE = "contracts/openapi.baseline.json";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

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
        } else {
            assertContract(mapper.readTree(findBaseline().toFile()), document);
        }
    }

    private static Path findBaseline() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        for (Path dir = workingDirectory; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(BASELINE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Cannot find " + BASELINE + " above " + workingDirectory);
    }

    static void assertContract(JsonNode baseline, JsonNode runtime) {
        assertThat(runtime.required("openapi")).as("OpenAPI version").isEqualTo(baseline.required("openapi"));
        assertSection("HTTP operations", operations(baseline), operations(runtime));
        assertSection("component schemas",
                mapValues(baseline.requiredAt("/components/schemas"), OpenApiBaselineTest::schema),
                mapValues(runtime.requiredAt("/components/schemas"), OpenApiBaselineTest::schema));
    }

    private static void assertSection(String section, ObjectNode baseline, ObjectNode runtime) {
        var names = new TreeSet<String>();
        baseline.properties().forEach(entry -> names.add(entry.getKey()));
        runtime.properties().forEach(entry -> names.add(entry.getKey()));
        var differences = new ArrayList<String>();
        for (String name : names) {
            if (!runtime.has(name)) {
                differences.add("missing: " + name);
            } else if (!baseline.has(name)) {
                differences.add("added: " + name);
            } else if (!baseline.get(name).equals(runtime.get(name))) {
                differences.add("changed: " + name);
            }
        }
        assertThat(differences).as("%s differ from %s; review contract changes before exporting", section, BASELINE)
                .isEmpty();
    }

    private static ObjectNode operations(JsonNode document) {
        var result = JSON.objectNode();
        document.required("paths").properties().forEach(path -> {
            var item = path.getValue();
            var pathParameters = item.has("parameters") ? parameters(item.get("parameters")) : null;
            for (String method : HTTP_METHODS) {
                if (!item.has(method)) {
                    continue;
                }
                var operation = item.get(method);
                var contract = JSON.objectNode();
                if (pathParameters != null) {
                    contract.set("pathParameters", pathParameters);
                }
                copy(contract, operation, "parameters", OpenApiBaselineTest::parameters);
                copy(contract, operation, "requestBody", body -> withContent(body, "$ref", "required"));
                copy(contract, operation, "responses",
                        responses -> mapValues(responses, response -> withContent(response, "$ref")));
                result.set(method.toUpperCase(Locale.ROOT) + " " + path.getKey(), contract);
            }
        });
        return result;
    }

    private static JsonNode parameters(JsonNode input) {
        var parameters = new ArrayList<JsonNode>();
        input.forEach(parameter -> {
            var contract = withContent(parameter, "$ref", "name", "in", "required", "style", "explode", "allowReserved");
            copy(contract, parameter, "schema", OpenApiBaselineTest::schema);
            parameters.add(contract);
        });
        // Parameter position in the generated array does not identify the parameter.
        parameters.sort(Comparator.comparing((JsonNode p) -> p.path("in").asText())
                .thenComparing(p -> p.path("name").asText()).thenComparing(p -> p.path("$ref").asText()));
        return JSON.arrayNode().addAll(parameters);
    }

    private static ObjectNode withContent(JsonNode input, String... fields) {
        var result = select(input, fields);
        copy(result, input, "content", content -> mapValues(content, media -> {
            var shape = JSON.objectNode();
            copy(shape, media, "schema", OpenApiBaselineTest::schema);
            return shape;
        }));
        return result;
    }

    private static JsonNode schema(JsonNode input) {
        if (!input.isObject()) {
            return input; // OpenAPI 3.1 also allows a boolean schema.
        }
        // Strip annotations only at schema nodes, never from maps of property names or defaults.
        ObjectNode result = input.deepCopy();
        result.remove(List.of("title", "description", "example", "examples", "externalDocs", "$comment"));
        for (String field : new String[]{"type", "required", "enum"}) {
            copy(result, input, field, OpenApiBaselineTest::unorderedValues);
        }
        for (String field : new String[]{"properties", "patternProperties", "$defs", "dependentSchemas"}) {
            copy(result, input, field, values -> mapValues(values, OpenApiBaselineTest::schema));
        }
        for (String field : new String[]{"items", "additionalProperties", "not", "contains", "propertyNames",
                "unevaluatedProperties", "unevaluatedItems", "if", "then", "else"}) {
            copy(result, input, field, OpenApiBaselineTest::schema);
        }
        for (String field : new String[]{"allOf", "anyOf", "oneOf"}) {
            copy(result, input, field, values -> unorderedValues(schemaArray(values)));
        }
        copy(result, input, "prefixItems", OpenApiBaselineTest::schemaArray);
        return result;
    }

    private static JsonNode schemaArray(JsonNode input) {
        var result = JSON.arrayNode();
        input.forEach(value -> result.add(schema(value)));
        return result;
    }

    private static JsonNode unorderedValues(JsonNode input) {
        if (!input.isArray()) {
            return input;
        }
        var values = new ArrayList<JsonNode>();
        input.forEach(value -> values.add(orderedObjectKeys(value)));
        values.sort(Comparator.comparing(JsonNode::toString));
        return JSON.arrayNode().addAll(values);
    }

    private static JsonNode orderedObjectKeys(JsonNode input) {
        if (input.isObject()) {
            var result = JSON.objectNode();
            input.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), orderedObjectKeys(entry.getValue())));
            return result;
        }
        if (input.isArray()) {
            var result = JSON.arrayNode();
            input.forEach(value -> result.add(orderedObjectKeys(value)));
            return result;
        }
        return input;
    }

    private static ObjectNode select(JsonNode source, String... fields) {
        var result = JSON.objectNode();
        for (String field : fields) {
            copy(result, source, field, UnaryOperator.identity());
        }
        return result;
    }

    private static ObjectNode mapValues(JsonNode source, UnaryOperator<JsonNode> projection) {
        var result = JSON.objectNode();
        source.properties().forEach(entry -> result.set(entry.getKey(), projection.apply(entry.getValue())));
        return result;
    }

    private static void copy(ObjectNode result, JsonNode source, String field, UnaryOperator<JsonNode> projection) {
        if (source.has(field)) {
            result.set(field, projection.apply(source.get(field)));
        }
    }
}


